package me.liwncy.agbot.kernel.api.agent;

import me.liwncy.agbot.kernel.api.message.MsgInfo;

import java.util.concurrent.CompletableFuture;

/**
 * 将入站消息交给 Agent（由 one-agent 实现）。
 */
public interface AgentBridge {

    CompletableFuture<AgentOutcome> handle(MsgInfo msgInfo);
}
