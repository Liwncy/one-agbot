package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.inbound.GolemMediaResolver;
import me.liwncy.agbot.adapter.golem.inbound.GolemMessageParser;
import me.liwncy.agbot.adapter.golem.inbound.GolemSignatureVerifier;
import me.liwncy.agbot.adapter.golem.session.GolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.GolemMentionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemOwnerCommandHandler;
import me.liwncy.agbot.adapter.golem.session.GolemSessionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemSessionCommandHandler;
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
    private final GolemMentionActivation mentionActivation;
    private final GolemSessionActivation sessionActivation;
    private final GolemSessionCommandHandler sessionCommandHandler;
    private final GolemOwnerCommandHandler ownerCommandHandler;
    private final GolemMediaResolver mediaResolver;

    public GolemWebhookController(AdapterRuntime runtime,
                                  GolemProperties properties,
                                  GolemGroupGate groupGate,
                                  GolemMentionActivation mentionActivation,
                                  GolemSessionActivation sessionActivation,
                                  GolemSessionCommandHandler sessionCommandHandler,
                                  GolemOwnerCommandHandler ownerCommandHandler,
                                  GolemMediaResolver mediaResolver) {
        this.runtime = runtime;
        this.properties = properties;
        this.groupGate = groupGate;
        this.mentionActivation = mentionActivation;
        this.sessionActivation = sessionActivation;
        this.sessionCommandHandler = sessionCommandHandler;
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
            log.warn("群聊仅@回复已开启，但未配置 bot-wechat-id / bot-wechat-name，群消息将被忽略");
        }

        String botId = trim(properties.getBotWechatId());
        int accepted = 0;
        int skippedNoMention = 0;
        int skippedDisabled = 0;
        int skippedInactive = 0;
        int sessionCommands = 0;
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
            boolean activated = !msg.isPrivateChat()
                    && mentionActivation.isActive(msg.accountId(), msg.groupId(), msg.userId());
            if (!msg.isPrivateChat()
                    && properties.isGroupRequireMention()
                    && !mentioned
                    && !activated) {
                skippedNoMention++;
                log.info("Skip no-mention group accountId={} groupId={} userId={} msg={} push={} msgSource={} botMentioned={}",
                        msg.accountId(),
                        msg.groupId(),
                        msg.userId(),
                        preview(msg.msg()),
                        preview(String.valueOf(msg.extra().getOrDefault("pushContent", ""))),
                        preview(String.valueOf(msg.extra().getOrDefault("msgSource", ""))),
                        msg.extra().get("botMentioned"));
                continue;
            }
            // 点名或窗口内跟聊：刷新连续对话窗口
            if (!msg.isPrivateChat() && (mentioned || activated)) {
                mentionActivation.touch(msg.accountId(), msg.groupId(), msg.userId());
            }
            accepted++;
            log.info("Accept message accountId={} groupId={} userId={} userName={} type={} mentioned={} activated={} msg={}",
                    msg.accountId(), msg.groupId(), msg.userId(), msg.userName(), msg.msgType(),
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
        // 全是未激活静默跳过时不刷 info，避免关闭会话刷屏
        if (accepted > 0 || sessionCommands > 0 || ownerCommands > 0
                || skippedNoMention > 0 || skippedDisabled > 0) {
            log.info("Golem webhook accountId={} accepted={} skippedNoMention={} skippedDisabled={} skippedInactive={} sessionCommands={} ownerCommands={}",
                    accountId, accepted, skippedNoMention, skippedDisabled, skippedInactive, sessionCommands, ownerCommands);
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
                "ownerCommands", ownerCommands
        );
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
