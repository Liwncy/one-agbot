package me.liwncy.agbot.adapter.example;

import me.liwncy.agbot.kernel.api.adapter.AdapterContext;
import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 示例适配器：platform=example，用内存保存最近一次 reply 供 HTTP 回包。
 */
@Component
public class ExampleAdapter implements ChatAdapter {
    public static final String PLATFORM = "example";
    private static final Logger log = LoggerFactory.getLogger(ExampleAdapter.class);

    private AdapterRuntime runtime;
    private final Map<String, ReplyInfo> lastReplies = new ConcurrentHashMap<>();

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public void init(AdapterContext ctx) {
        this.runtime = ctx.runtime();
    }

    @Override
    public void start() {
        log.info("Example adapter started");
    }

    @Override
    public void stop() {
        lastReplies.clear();
        log.info("Example adapter stopped");
    }

    @Override
    public CompletableFuture<String> reply(ReplyInfo replyInfo) {
        String key = replyKey(replyInfo.accountId(), replyInfo.userId(), replyInfo.groupId());
        lastReplies.put(key, replyInfo);
        String msgId = UUID.randomUUID().toString().replace("-", "");
        log.debug("Example reply stored key={} msgId={}", key, msgId);
        return CompletableFuture.completedFuture(msgId);
    }

    @Override
    public CompletableFuture<Void> delMsg(List<String> msgIds) {
        log.info("Example delMsg ids={}", msgIds);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Map<String, Object> bridge() {
        return Map.of("echo", "example-bridge");
    }

    public AdapterRuntime runtime() {
        return runtime;
    }

    public ReplyInfo lastReply(String accountId, String userId, String groupId) {
        return lastReplies.get(replyKey(accountId, userId, groupId));
    }

    private static String replyKey(String accountId, String userId, String groupId) {
        return (accountId == null ? "default" : accountId) + ":"
                + (groupId == null || groupId.isBlank() || "0".equals(groupId) ? userId : groupId);
    }
}
