package me.liwncy.agbot.kernel.api.runtime;

import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 适配器运行时：入站编排与出站调度。
 */
public interface AdapterRuntime {

    void register(ChatAdapter adapter);

    Optional<ChatAdapter> find(String platform);

    /**
     * 对应 Bncr Adapter.receive。
     */
    CompletableFuture<ReplyInfo> receive(MsgInfo msgInfo);

    CompletableFuture<String> push(String platform, ReplyInfo replyInfo);
}
