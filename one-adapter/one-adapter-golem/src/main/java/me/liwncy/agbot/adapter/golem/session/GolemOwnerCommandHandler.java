package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.adapter.golem.inbound.GolemMentionDetector;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * 主人在群内控制机器人启停（不走 Agent）。
 */
@Component
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemOwnerCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(GolemOwnerCommandHandler.class);

    private final GolemProperties properties;
    private final GolemGroupGate groupGate;
    private final GolemGroupModeCommandHandler groupModeCommandHandler;
    private final GolemApiClient apiClient;

    public GolemOwnerCommandHandler(GolemProperties properties,
                                    GolemGroupGate groupGate,
                                    GolemGroupModeCommandHandler groupModeCommandHandler,
                                    GolemApiClient apiClient) {
        this.properties = properties;
        this.groupGate = groupGate;
        this.groupModeCommandHandler = groupModeCommandHandler;
        this.apiClient = apiClient;
    }

    /**
     * @return 已处理则返回 true（不再交给 Agent）
     */
    public boolean tryHandle(MsgInfo msg) {
        // 会话激活模式下由 GolemSessionCommandHandler 统一处理
        if (msg == null || msg.isPrivateChat() || properties.isSessionRequireActivation()) {
            return false;
        }
        String ownerId = trim(properties.getOwnerWechatId());
        if (ownerId.isEmpty()) {
            return false;
        }

        String command = normalizeCommand(msg.msg());
        Optional<Action> action = parseAction(command);
        if (action.isEmpty()) {
            return false;
        }

        String receiver = msg.groupId();
        if (!ownerId.equals(trim(msg.userId()))) {
            reply(receiver, "这事只有主人能定");
            return true;
        }

        return switch (action.get()) {
            case ENABLE -> {
                groupGate.enable(msg.accountId(), msg.groupId());
                reply(receiver, "好了，这个群继续聊");
                log.info("Group enabled by owner accountId={} groupId={}", msg.accountId(), msg.groupId());
                yield true;
            }
            case DISABLE -> {
                groupGate.disable(msg.accountId(), msg.groupId());
                reply(receiver, "好了，这个群先歇着");
                log.info("Group disabled by owner accountId={} groupId={}", msg.accountId(), msg.groupId());
                yield true;
            }
            case STATUS -> {
                boolean on = groupGate.isEnabled(msg.accountId(), msg.groupId());
                String modeLine = groupModeCommandHandler.statusLine(msg);
                reply(receiver, on
                        ? ("这个群开着呢；" + modeLine)
                        : ("这个群歇着呢；" + modeLine));
                yield true;
            }
        };
    }

    private String normalizeCommand(String raw) {
        String text = GolemMentionDetector.stripMentionPrefix(
                raw, properties.getBotWechatId(), properties.getBotWechatName());
        return text == null ? "" : text.trim();
    }

    private static Optional<Action> parseAction(String command) {
        if (command.isEmpty()) {
            return Optional.empty();
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "启用", "开机", "开始", "开", "on", "start", "enable" -> Optional.of(Action.ENABLE);
            case "停用", "关机", "停止", "关", "off", "stop", "disable" -> Optional.of(Action.DISABLE);
            case "状态", "status" -> Optional.of(Action.STATUS);
            default -> Optional.empty();
        };
    }

    private void reply(String receiver, String content) {
        try {
            apiClient.sendText(receiver, content);
        } catch (Exception e) {
            log.error("Owner command reply failed receiver={}", receiver, e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private enum Action {
        ENABLE, DISABLE, STATUS
    }
}
