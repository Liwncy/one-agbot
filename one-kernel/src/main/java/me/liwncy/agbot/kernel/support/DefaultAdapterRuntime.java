package me.liwncy.agbot.kernel.support;

import me.liwncy.agbot.common.core.exception.ServiceException;
import me.liwncy.agbot.common.log.ChannelLog;
import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.agent.AgentOutcome;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Runtime：过期丢弃 → Agent → reply。
 */
public class DefaultAdapterRuntime implements AdapterRuntime {
    private static final Logger log = LoggerFactory.getLogger(DefaultAdapterRuntime.class);

    private final Map<String, ChatAdapter> adapters = new ConcurrentHashMap<>();
    private final AgentBridge agentBridge;
    private final KernelProperties properties;

    public DefaultAdapterRuntime(AgentBridge agentBridge, KernelProperties properties) {
        this.agentBridge = agentBridge;
        this.properties = properties;
    }

    @Override
    public void register(ChatAdapter adapter) {
        adapters.put(adapter.platform(), adapter);
        log.info("Registered adapter platform={}", adapter.platform());
    }

    @Override
    public Optional<ChatAdapter> find(String platform) {
        return Optional.ofNullable(adapters.get(platform));
    }

    @Override
    public CompletableFuture<ReplyInfo> receive(MsgInfo msgInfo) {
        if (msgInfo == null) {
            return CompletableFuture.failedFuture(new ServiceException("msgInfo is null"));
        }
        long age = System.currentTimeMillis() - msgInfo.createTime();
        if (age > properties.getMaxMessageAge().toMillis()) {
            log.warn("Drop stale message platform={} msgId={} ageMs={}",
                    msgInfo.platform(), msgInfo.msgId(), age);
            return CompletableFuture.completedFuture(null);
        }

        ChatAdapter adapter = adapters.get(msgInfo.platform());
        if (adapter == null) {
            return CompletableFuture.failedFuture(
                    new ServiceException("adapter not found: " + msgInfo.platform()));
        }

        ChannelLog.inbound(msgInfo.platform(), msgInfo.accountId(),
                "userId=" + msgInfo.userId() + " groupId=" + msgInfo.groupId() + " msg=" + msgInfo.msg());

        return agentBridge.handle(msgInfo).thenCompose(outcome -> {
            if (outcome instanceof AgentOutcome.Handled handled) {
                log.info("Agent handled without local reply: {}", handled.reason());
                return CompletableFuture.completedFuture(null);
            }
            if (outcome instanceof AgentOutcome.Reply reply) {
                ReplyInfo merged = ReplyInfo.merge(reply.replyInfo(), msgInfo);
                ChannelLog.outbound(msgInfo.platform(), msgInfo.accountId(),
                        "type=" + merged.type() + " msg=" + merged.msg());
                return adapter.reply(merged).thenApply(msgId -> merged);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    @Override
    public CompletableFuture<String> push(String platform, ReplyInfo replyInfo) {
        ChatAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return CompletableFuture.failedFuture(new ServiceException("adapter not found: " + platform));
        }
        return adapter.reply(replyInfo);
    }
}
