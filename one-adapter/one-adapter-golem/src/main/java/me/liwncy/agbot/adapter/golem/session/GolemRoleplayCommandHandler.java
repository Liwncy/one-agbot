package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.adapter.golem.inbound.GolemMentionDetector;
import me.liwncy.agbot.kernel.api.agent.RoleplayCommands;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 切角色口令与「模式」同一层：点名门之前拦截，不进 Agent。
 */
@Component
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemRoleplayCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(GolemRoleplayCommandHandler.class);

    private final GolemProperties properties;
    private final GolemApiClient apiClient;
    private final ObjectProvider<RoleplayCommands> roleplay;

    public GolemRoleplayCommandHandler(GolemProperties properties,
                                       GolemApiClient apiClient,
                                       ObjectProvider<RoleplayCommands> roleplay) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.roleplay = roleplay;
    }

    /**
     * @return 已处理则 true（不再交给 Agent）
     */
    public boolean tryHandle(MsgInfo msg) {
        RoleplayCommands commands = roleplay.getIfAvailable();
        if (commands == null || msg == null) {
            return false;
        }
        if (!MsgType.TEXT.equals(MsgType.normalize(msg.msgType()))) {
            return false;
        }
        String command = GolemMentionDetector.stripMentionPrefix(
                msg.msg(), properties.getBotWechatId(), properties.getBotWechatName());
        String reply = commands.tryHandle(msg, command);
        if (reply == null || reply.isBlank()) {
            return false;
        }
        reply(resolveReceiver(msg), reply);
        log.info("Roleplay command handled accountId={} groupId={} userId={} reply={}",
                msg.accountId(), msg.groupId(), msg.userId(), preview(reply));
        return true;
    }

    private static String resolveReceiver(MsgInfo msg) {
        if (!msg.isPrivateChat()) {
            return msg.groupId();
        }
        return msg.userId();
    }

    private void reply(String receiver, String content) {
        try {
            apiClient.sendText(receiver, content);
        } catch (Exception e) {
            log.error("Roleplay command reply failed receiver={}", receiver, e);
        }
    }

    private static String preview(String msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.replace('\n', ' ').trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }
}
