package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.inbound.GolemMessageParser;
import me.liwncy.agbot.adapter.golem.inbound.GolemSignatureVerifier;
import me.liwncy.agbot.adapter.golem.session.GolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.GolemOwnerCommandHandler;
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
    private final GolemOwnerCommandHandler ownerCommandHandler;

    public GolemWebhookController(AdapterRuntime runtime,
                                  GolemProperties properties,
                                  GolemGroupGate groupGate,
                                  GolemOwnerCommandHandler ownerCommandHandler) {
        this.runtime = runtime;
        this.properties = properties;
        this.groupGate = groupGate;
        this.ownerCommandHandler = ownerCommandHandler;
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
        int ownerCommands = 0;
        for (MsgInfo msg : messages) {
            if (!botId.isEmpty() && botId.equals(msg.userId())) {
                continue;
            }

            // 主人启停指令优先：群已停用时仍可「开机」
            if (ownerCommandHandler.tryHandle(msg)) {
                ownerCommands++;
                continue;
            }

            if (!msg.isPrivateChat() && !groupGate.isEnabled(msg.accountId(), msg.groupId())) {
                skippedDisabled++;
                continue;
            }
            if (!msg.isPrivateChat() && properties.isGroupRequireMention() && !isBotMentioned(msg)) {
                skippedNoMention++;
                continue;
            }
            accepted++;
            runtime.receive(msg).whenComplete((reply, err) -> {
                if (err != null) {
                    log.error("Golem handle failed accountId={} msgId={}", accountId, msg.msgId(), err);
                }
            });
        }
        log.info("Golem webhook accountId={} accepted={} skippedNoMention={} skippedDisabled={} ownerCommands={}",
                accountId, accepted, skippedNoMention, skippedDisabled, ownerCommands);
        return Map.of(
                "success", true,
                "accepted", accepted,
                "skippedNoMention", skippedNoMention,
                "skippedDisabled", skippedDisabled,
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
}
