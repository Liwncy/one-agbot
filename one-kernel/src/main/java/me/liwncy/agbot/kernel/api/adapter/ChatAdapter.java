package me.liwncy.agbot.kernel.api.adapter;

import me.liwncy.agbot.kernel.api.message.ReplyInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 平台适配器 SPI（对齐 Bncr Adapter：reply/push/delMsg/Bridge）。
 */
public interface ChatAdapter {

    String platform();

    void init(AdapterContext ctx);

    void start();

    void stop();

    CompletableFuture<String> reply(ReplyInfo replyInfo);

    default CompletableFuture<Void> push(ReplyInfo replyInfo) {
        return reply(replyInfo).thenApply(id -> null);
    }

    default CompletableFuture<Void> delMsg(List<String> msgIds) {
        return CompletableFuture.completedFuture(null);
    }

    default Map<String, Object> bridge() {
        return Map.of();
    }
}
