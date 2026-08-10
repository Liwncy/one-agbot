package me.liwncy.agbot.adapter.example;

import me.liwncy.agbot.kernel.api.adapter.AdapterContext;
import me.liwncy.agbot.kernel.api.adapter.ChannelCapabilities;
import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.message.MsgType;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 示例适配器：platform=example，内存覆盖通道契约上限类型，供联调。
 */
@Component
public class ExampleAdapter implements ChatAdapter {
    public static final String PLATFORM = "example";
    private static final Logger log = LoggerFactory.getLogger(ExampleAdapter.class);

    private static final ChannelCapabilities CAPABILITIES = ChannelCapabilities.all();

    private AdapterRuntime runtime;
    private final Map<String, ReplyInfo> lastReplies = new ConcurrentHashMap<>();
    private final List<ReplyInfo> outboundLog = new CopyOnWriteArrayList<>();
    private final List<String> deletedMsgIds = new CopyOnWriteArrayList<>();

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
        log.info("Example adapter started capabilities={}", CAPABILITIES);
    }

    @Override
    public void stop() {
        lastReplies.clear();
        outboundLog.clear();
        deletedMsgIds.clear();
        log.info("Example adapter stopped");
    }

    @Override
    public ChannelCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletableFuture<String> reply(ReplyInfo replyInfo) {
        return store(replyInfo, "reply");
    }

    @Override
    public CompletableFuture<String> push(ReplyInfo replyInfo) {
        return store(replyInfo, "push");
    }

    @Override
    public CompletableFuture<Void> delMsg(List<String> msgIds) {
        if (msgIds != null) {
            deletedMsgIds.addAll(msgIds);
        }
        log.info("Example delMsg ids={}", msgIds);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Map<String, Object> bridge() {
        return Map.of(
                "echo", "example-bridge",
                "outboundCount", outboundLog.size(),
                "deletedCount", deletedMsgIds.size()
        );
    }

    public AdapterRuntime runtime() {
        return runtime;
    }

    public ReplyInfo lastReply(String accountId, String userId, String groupId) {
        return lastReplies.get(replyKey(accountId, userId, groupId));
    }

    public List<ReplyInfo> outboundLog() {
        return List.copyOf(outboundLog);
    }

    public List<String> deletedMsgIds() {
        return List.copyOf(deletedMsgIds);
    }

    private CompletableFuture<String> store(ReplyInfo replyInfo, String via) {
        if (replyInfo == null) {
            return CompletableFuture.completedFuture("");
        }
        String type = MsgType.normalize(replyInfo.type());
        if (!CAPABILITIES.supportsOutbound(type)) {
            log.warn("Example unsupported type={} via={}", type, via);
            return CompletableFuture.completedFuture("");
        }
        String key = replyKey(replyInfo.accountId(), replyInfo.userId(), replyInfo.groupId());
        lastReplies.put(key, replyInfo);
        outboundLog.add(replyInfo);
        String msgId = UUID.randomUUID().toString().replace("-", "");
        log.info("Example {} stored key={} type={} msgId={} path={}",
                via, key, type, msgId, replyInfo.path());
        return CompletableFuture.completedFuture(msgId);
    }

    private static String replyKey(String accountId, String userId, String groupId) {
        return (accountId == null ? "default" : accountId) + ":"
                + (groupId == null || groupId.isBlank() || "0".equals(groupId) ? userId : groupId);
    }
}
