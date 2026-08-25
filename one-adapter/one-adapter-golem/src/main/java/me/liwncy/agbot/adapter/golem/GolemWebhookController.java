package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.inbound.GolemMediaResolver;
import me.liwncy.agbot.adapter.golem.inbound.GolemMessageParser;
import me.liwncy.agbot.adapter.golem.inbound.GolemSignatureVerifier;
import me.liwncy.agbot.adapter.golem.session.GolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.GolemGroupModeCommandHandler;
import me.liwncy.agbot.adapter.golem.session.GolemGroupRespondMode;
import me.liwncy.agbot.adapter.golem.session.GolemGroupRespondPolicy;
import me.liwncy.agbot.adapter.golem.session.GolemMentionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemOwnerCommandHandler;
import me.liwncy.agbot.adapter.golem.session.GolemSessionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemSessionCommandHandler;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import me.liwncy.agbot.kernel.api.session.ConversationTurnGuard;
import me.liwncy.agbot.kernel.api.session.SessionKeys;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Golem 推送入口（对齐 xchatbot POST /webhook/wechat）。
 */
@RestController
@RequestMapping("/adapter/golem")
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemWebhookController {
    private static final Logger log = LoggerFactory.getLogger(GolemWebhookController.class);

    private final AdapterRuntime runtime;
    private final GolemProperties properties;
    private final GolemGroupGate groupGate;
    private final GolemGroupRespondPolicy respondPolicy;
    private final GolemMentionActivation mentionActivation;
    private final GolemSessionActivation sessionActivation;
    private final GolemSessionCommandHandler sessionCommandHandler;
    private final GolemGroupModeCommandHandler groupModeCommandHandler;
    private final GolemOwnerCommandHandler ownerCommandHandler;
    private final GolemMediaResolver mediaResolver;
    private final ChatLogService chatLog;
    private final ConversationTurnGuard turnGuard;

    public GolemWebhookController(AdapterRuntime runtime,
                                  GolemProperties properties,
                                  GolemGroupGate groupGate,
                                  GolemGroupRespondPolicy respondPolicy,
                                  GolemMentionActivation mentionActivation,
                                  GolemSessionActivation sessionActivation,
                                  GolemSessionCommandHandler sessionCommandHandler,
                                  GolemGroupModeCommandHandler groupModeCommandHandler,
                                  GolemOwnerCommandHandler ownerCommandHandler,
                                  GolemMediaResolver mediaResolver,
                                  ObjectProvider<ChatLogService> chatLog,
                                  ConversationTurnGuard turnGuard) {
        this.runtime = runtime;
        this.properties = properties;
        this.groupGate = groupGate;
        this.respondPolicy = respondPolicy;
        this.mentionActivation = mentionActivation;
        this.sessionActivation = sessionActivation;
        this.sessionCommandHandler = sessionCommandHandler;
        this.groupModeCommandHandler = groupModeCommandHandler;
        this.ownerCommandHandler = ownerCommandHandler;
        this.mediaResolver = mediaResolver;
        this.chatLog = chatLog.getIfAvailable();
        this.turnGuard = turnGuard;
    }

    @PostMapping("/{accountId}/webhook")
    public Map<String, Object> onWebhook(@PathVariable String accountId,
                                         @RequestHeader(value = "x-signature", required = false) String signature,
                                         @RequestHeader(value = "x-timestamp", required = false) String timestamp,
                                         @RequestBody String rawBody) {
        if (!GolemSignatureVerifier.verify(properties.getWebhookToken(), signature, timestamp, rawBody)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid signature");
        }

        List<MsgInfo> messages = GolemMessageParser.parse(accountId, rawBody, properties);
        if (messages.isEmpty()) {
            log.info("Golem webhook no message parsed accountId={} bodyPreview={}",
                    accountId, preview(rawBody));
            return Map.of("success", true, "skipped", true);
        }

        boolean mentionConfigMissing = isBlank(properties.getBotWechatId()) && isBlank(properties.getBotWechatName());
        if (properties.isGroupRequireMention() && mentionConfigMissing) {
            log.warn("默认群模式为点名，但未配置 bot-wechat-id / bot-wechat-name，点名识别可能失效");
        }

        String botId = trim(properties.getBotWechatId());
        int accepted = 0;
        int skippedNoMention = 0;
        int skippedDisabled = 0;
        int skippedInactive = 0;
        int sessionCommands = 0;
        int modeCommands = 0;
        int ownerCommands = 0;
        for (MsgInfo raw : messages) {
            MsgInfo msg = mediaResolver.resolve(raw);
            if (!botId.isEmpty() && botId.equals(msg.userId())) {
                continue;
            }
            recordInbound(msg);

            // 会话启停指令优先（未激活时也能「开始」）
            if (sessionCommandHandler.tryHandle(msg)) {
                sessionCommands++;
                continue;
            }
            // 群模式 / 规则指令（主人，未激活也可改）
            if (groupModeCommandHandler.tryHandle(msg)) {
                modeCommands++;
                continue;
            }

            if (properties.isSessionRequireActivation()) {
                String peerKey = GolemSessionActivation.peerKey(msg);
                if (!sessionActivation.isActive(msg.accountId(), peerKey)) {
                    skippedInactive++;
                    log.debug("Skip inactive session accountId={} peerKey={} msg={}",
                            msg.accountId(), peerKey, preview(msg.msg()));
                    continue;
                }
            } else {
                // 兼容旧模式：群门禁 + 主人群指令
                if (ownerCommandHandler.tryHandle(msg)) {
                    ownerCommands++;
                    continue;
                }
                if (!msg.isPrivateChat() && !groupGate.isEnabled(msg.accountId(), msg.groupId())) {
                    skippedDisabled++;
                    log.info("Skip disabled group accountId={} groupId={} msg={}",
                            msg.accountId(), msg.groupId(), preview(msg.msg()));
                    continue;
                }
            }

            boolean mentioned = isBotMentioned(msg);
            GolemGroupRespondMode mode = msg.isPrivateChat()
                    ? null
                    : respondPolicy.getMode(msg.accountId(), msg.groupId());
            boolean followUpApplies = mode == GolemGroupRespondMode.MENTION
                    || mode == GolemGroupRespondMode.SMART
                    || mode == GolemGroupRespondMode.RULE
                    || mode == GolemGroupRespondMode.FULL;
            Duration followUp = !followUpApplies
                    ? Duration.ZERO
                    : respondPolicy.getFollowUpWindow(msg.accountId(), msg.groupId());
            boolean activated = !msg.isPrivateChat()
                    && followUpApplies
                    && mentionActivation.isActive(msg.accountId(), msg.groupId(), msg.userId(), followUp);
            boolean conversationBusy = !msg.isPrivateChat() && turnGuard.isBusy(SessionKeys.of(msg));
            if (!msg.isPrivateChat() && !respondPolicy.allows(msg, mentioned, activated, conversationBusy)) {
                skippedNoMention++;
                log.info("Skip by group mode accountId={} groupId={} userId={} mode={} followUp={}s mentioned={} activated={} busy={} msg={}",
                        msg.accountId(),
                        msg.groupId(),
                        msg.userId(),
                        mode,
                        followUp.toSeconds(),
                        mentioned,
                        activated,
                        conversationBusy,
                        preview(msg.msg()));
                continue;
            }
            // 跟聊只挂在点名（智能/规则/全量忙时也靠点名窗）；随机命中不续窗
            if (followUpApplies && (mentioned || activated)) {
                mentionActivation.touch(msg.accountId(), msg.groupId(), msg.userId(), followUp);
            }
            accepted++;
            log.info("Accept message accountId={} groupId={} userId={} userName={} type={} mode={} followUp={}s mentioned={} activated={} msg={}",
                    msg.accountId(), msg.groupId(), msg.userId(), msg.userName(), msg.msgType(),
                    msg.isPrivateChat() ? "-" : respondPolicy.getMode(msg.accountId(), msg.groupId()),
                    followUp.toSeconds(),
                    mentioned, activated, preview(msg.msg()));
            runtime.receive(msg).whenComplete((reply, err) -> {
                if (err != null) {
                    log.error("Golem handle failed accountId={} msgId={} msg={}",
                            accountId, msg.msgId(), preview(msg.msg()), err);
                } else if (reply != null) {
                    log.info("Golem reply sent accountId={} msgId={} reply={}",
                            accountId, msg.msgId(), preview(reply.msg()));
                }
            });
        }
        if (accepted > 0 || sessionCommands > 0 || modeCommands > 0 || ownerCommands > 0
                || skippedNoMention > 0 || skippedDisabled > 0) {
            log.info("Golem webhook accountId={} accepted={} skippedMode={} skippedDisabled={} skippedInactive={} sessionCommands={} modeCommands={} ownerCommands={}",
                    accountId, accepted, skippedNoMention, skippedDisabled, skippedInactive,
                    sessionCommands, modeCommands, ownerCommands);
        } else if (skippedInactive > 0) {
            log.debug("Golem webhook accountId={} skippedInactive={}", accountId, skippedInactive);
        }
        return Map.of(
                "success", true,
                "accepted", accepted,
                "skippedNoMention", skippedNoMention,
                "skippedDisabled", skippedDisabled,
                "skippedInactive", skippedInactive,
                "sessionCommands", sessionCommands,
                "modeCommands", modeCommands,
                "ownerCommands", ownerCommands
        );
    }

    private void recordInbound(MsgInfo msg) {
        if (chatLog == null) {
            return;
        }
        try {
            chatLog.recordInbound(msg);
        } catch (Exception e) {
            log.warn("Chat log inbound failed accountId={} msgId={}: {}",
                    msg.accountId(), msg.msgId(), e.getMessage());
        }
    }

    private static boolean isBotMentioned(MsgInfo msg) {
        Object flag = msg.extra().get("botMentioned");
        return Boolean.TRUE.equals(flag);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String preview(String msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.replace('\n', ' ').trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }
}
