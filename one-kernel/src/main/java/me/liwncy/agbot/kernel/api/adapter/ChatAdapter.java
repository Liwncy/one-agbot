package me.liwncy.agbot.kernel.api.adapter;

import me.liwncy.agbot.kernel.api.message.ReplyInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 平台适配器 SPI（对齐 Bncr Adapter：reply/push/delMsg/Bridge）。
 * <p>能力上限见 {@link ChannelCapabilities#all()}；实现方应覆盖 {@link #capabilities()}。</p>
 */
public interface ChatAdapter {

    String platform();

    void init(AdapterContext ctx);

    void start();

    void stop();

    CompletableFuture<String> reply(ReplyInfo replyInfo);

    /**
     * 主动触达；默认委托 {@link #reply(ReplyInfo)}，返回平台消息 id。
     */
    default CompletableFuture<String> push(ReplyInfo replyInfo) {
        return reply(replyInfo);
    }

    default CompletableFuture<Void> delMsg(List<String> msgIds) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 本适配器实际支持的通道能力（默认仅文本，避免虚报上限）。
     */
    default ChannelCapabilities capabilities() {
        return ChannelCapabilities.textOnly();
    }

    /**
     * 平台逃逸舱（登录/通讯录等），不保证跨适配器。
     */
    default Map<String, Object> bridge() {
        return Map.of();
    }
}
