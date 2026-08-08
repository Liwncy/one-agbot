package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.kernel.api.adapter.AdapterContext;
import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Golem（微信个人号网关）适配器，platform=golem。
 */
@Component
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemAdapter implements ChatAdapter {
    public static final String PLATFORM = "golem";
    private static final Logger log = LoggerFactory.getLogger(GolemAdapter.class);

    private final GolemApiClient apiClient;

    public GolemAdapter(GolemApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public void init(AdapterContext ctx) {
        // no-op
    }

    @Override
    public void start() {
        log.info("Golem adapter started");
    }

    @Override
    public void stop() {
        log.info("Golem adapter stopped");
    }

    @Override
    public CompletableFuture<String> reply(ReplyInfo replyInfo) {
        return CompletableFuture.supplyAsync(() -> {
            if (replyInfo == null) {
                return "";
            }
            String type = replyInfo.type() == null ? MsgType.TEXT : replyInfo.type();
            if (!MsgType.TEXT.equalsIgnoreCase(type)) {
                log.warn("Golem MVP 暂只支持文本回复，忽略 type={}", type);
                return "";
            }
            String receiver = resolveReceiver(replyInfo);
            if (receiver == null || receiver.isBlank()) {
                throw new IllegalStateException("Golem reply missing receiver");
            }
            String msgId = apiClient.sendText(receiver, replyInfo.msg());
            log.debug("Golem text sent receiver={} msgId={}", receiver, msgId);
            return msgId;
        });
    }

    private static String resolveReceiver(ReplyInfo replyInfo) {
        String groupId = replyInfo.groupId();
        if (groupId != null && !groupId.isBlank() && !"0".equals(groupId)) {
            return groupId;
        }
        return replyInfo.userId();
    }
}
