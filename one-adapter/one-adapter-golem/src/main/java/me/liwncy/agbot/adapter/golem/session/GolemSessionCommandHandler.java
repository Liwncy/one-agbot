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
 * 会话启停指令：默认不激活，收到指令后才允许进 Agent（私聊本人 / 群聊主人）。
 */
@Component
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemSessionCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(GolemSessionCommandHandler.class);

    private final GolemProperties properties;
    private final GolemSessionActivation sessionActivation;
    private final GolemGroupRespondPolicy respondPolicy;
    private final GolemGroupModeCommandHandler groupModeCommandHandler;
    private final GolemApiClient apiClient;

    public GolemSessionCommandHandler(GolemProperties properties,
                                      GolemSessionActivation sessionActivation,
                                      GolemGroupRespondPolicy respondPolicy,
                                      GolemGroupModeCommandHandler groupModeCommandHandler,
                                      GolemApiClient apiClient) {
        this.properties = properties;
        this.sessionActivation = sessionActivation;
        this.respondPolicy = respondPolicy;
        this.groupModeCommandHandler = groupModeCommandHandler;
        this.apiClient = apiClient;
    }

    /**
     * @return 已处理指令则 true（不再交给 Agent）
     */
    public boolean tryHandle(MsgInfo msg) {
        if (msg == null || !properties.isSessionRequireActivation()) {
            return false;
        }
        String command = normalizeCommand(msg.msg());
        Optional<Action> action = parseAction(command);
        if (action.isEmpty()) {
            return false;
        }

        if (!msg.isPrivateChat()) {
            String ownerId = trim(properties.getOwnerWechatId());
            if (ownerId.isEmpty()) {
                reply(resolveReceiver(msg), "群里还没设主人，没法开");
                return true;
            }
            if (!ownerId.equals(trim(msg.userId()))) {
                reply(resolveReceiver(msg), "这事只有主人能定");
                return true;
            }
        }

        String peerKey = GolemSessionActivation.peerKey(msg);
        String receiver = resolveReceiver(msg);
        return switch (action.get()) {
            case ENABLE -> {
                sessionActivation.activate(msg.accountId(), peerKey);
                if (!msg.isPrivateChat()) {
                    // 首次开启写入默认群配置：点名 + 跟聊关
                    respondPolicy.ensureDefaults(msg.accountId(), msg.groupId());
                    reply(receiver, "好了，这个群继续聊；" + groupModeCommandHandler.statusLine(msg));
                } else {
                    reply(receiver, "好了，来聊吧");
                }
                log.info("Session activated accountId={} peerKey={}", msg.accountId(), peerKey);
                yield true;
            }
            case DISABLE -> {
                sessionActivation.deactivate(msg.accountId(), peerKey);
                reply(receiver, msg.isPrivateChat() ? "好了，先不聊了" : "好了，这个群先歇着");
                log.info("Session deactivated accountId={} peerKey={}", msg.accountId(), peerKey);
                yield true;
            }
            case STATUS -> {
                boolean on = sessionActivation.isActive(msg.accountId(), peerKey);
                if (msg.isPrivateChat()) {
                    reply(receiver, on ? "开着呢，直接说就行" : "还没开始，发「开始」找我");
                } else {
                    String modeLine = groupModeCommandHandler.statusLine(msg);
                    reply(receiver, on
                            ? ("这个群开着呢；" + modeLine)
                            : ("这个群歇着呢；" + modeLine + "（先「开始」再聊）"));
                }
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
            case "停用", "关机", "停止", "关", "结束", "off", "stop", "disable" -> Optional.of(Action.DISABLE);
            case "状态", "status" -> Optional.of(Action.STATUS);
            default -> Optional.empty();
        };
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
            log.error("Session command reply failed receiver={}", receiver, e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private enum Action {
        ENABLE, DISABLE, STATUS
    }
}
