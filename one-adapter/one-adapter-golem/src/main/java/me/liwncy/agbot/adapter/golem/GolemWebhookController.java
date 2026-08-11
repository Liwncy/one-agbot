package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.inbound.GolemMediaResolver;
import me.liwncy.agbot.adapter.golem.inbound.GolemMessageParser;
import me.liwncy.agbot.adapter.golem.inbound.GolemSignatureVerifier;
import me.liwncy.agbot.adapter.golem.inbound.GolemMentionDetector;
import me.liwncy.agbot.adapter.golem.session.GolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.GolemGroupModeCommandHandler;
import me.liwncy.agbot.adapter.golem.session.GolemGroupRespondMode;
import me.liwncy.agbot.adapter.golem.session.GolemGroupRespondPolicy;
import me.liwncy.agbot.adapter.golem.session.GolemMentionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemOwnerCommandHandler;
import me.liwncy.agbot.adapter.golem.session.GolemSessionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemSessionCommandHandler;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public GolemWebhookController(AdapterRuntime runtime,
                                  GolemProperties properties,
                                  GolemGroupGate groupGate,
                                  GolemGroupRespondPolicy respondPolicy,
                                  GolemMentionActivation mentionActivation,
                                  GolemSessionActivation sessionActivation,
                                  GolemSessionCommandHandler sessionCommandHandler,
                                  GolemGroupModeCommandHandler groupModeCommandHandler,
                                  GolemOwnerCommandHandler ownerCommandHandler,
                                  GolemMediaResolver mediaResolver) {
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
            String pushContent = stringExtra(msg, "pushContent");
            // 跟别人说话（atuserlist / 正文 @别人）时不接——全量、随机、跟聊窗一律让路
            if (!msg.isPrivateChat()
                    && GolemMentionDetector.isTalkingToOthersOnly(
                    mentionIds(msg),
                    msg.msg(),
                    pushContent,
                    properties.getBotWechatId(),
                    properties.getBotWechatName())) {
                skippedNoMention++;
                log.info("Skip talking-to-others accountId={} groupId={} userId={} mentionIds={} msg={}",
                        msg.accountId(), msg.groupId(), msg.userId(),
                        mentionIds(msg), preview(msg.msg()));
                continue;
            }
            Duration followUp = msg.isPrivateChat()
                    ? Duration.ZERO
                    : respondPolicy.getFollowUpWindow(msg.accountId(), msg.groupId());
            boolean activated = !msg.isPrivateChat()
                    && mentionActivation.isActive(msg.accountId(), msg.groupId(), msg.userId(), followUp);
            if (!msg.isPrivateChat() && !respondPolicy.allows(msg, mentioned, activated)) {
                skippedNoMention++;
                log.info("Skip by group mode accountId={} groupId={} userId={} mode={} followUp={}s mentioned={} activated={} msg={}",
                        msg.accountId(),
                        msg.groupId(),
                        msg.userId(),
                        respondPolicy.getMode(msg.accountId(), msg.groupId()),
                        followUp.toSeconds(),
                        mentioned,
                        activated,
                        preview(msg.msg()));
                continue;
            }
            // 点名/跟聊续窗；随机或智能放行后也可续（若开了跟聊）
            GolemGroupRespondMode mode = msg.isPrivateChat()
                    ? null
                    : respondPolicy.getMode(msg.accountId(), msg.groupId());
            if (!msg.isPrivateChat() && (mentioned || activated
                    || mode == GolemGroupRespondMode.RANDOM
                    || mode == GolemGroupRespondMode.SMART)) {
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

    private static boolean isBotMentioned(MsgInfo msg) {
        Object flag = msg.extra().get("botMentioned");
        return Boolean.TRUE.equals(flag);
    }

    private static String stringExtra(MsgInfo msg, String key) {
        if (msg == null || msg.extra() == null || key == null) {
            return "";
        }
        Object v = msg.extra().get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> mentionIds(MsgInfo msg) {
        if (msg == null || msg.extra() == null) {
            return List.of();
        }
        Object raw = msg.extra().get(ChannelExtraKeys.MENTION_IDS);
        if (raw instanceof List<?> list) {
            List<String> out = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return out;
        }
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return List.of(String.valueOf(raw).trim().split("[,\\s]+"));
        }
        return List.of();
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
