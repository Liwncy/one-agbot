package me.liwncy.agbot.kernel.api.agent;

import me.liwncy.agbot.kernel.api.message.ReplyInfo;

/**
 * Agent 处理结果：同步回复或已接管（Handled）。
 */
public sealed interface AgentOutcome permits AgentOutcome.Reply, AgentOutcome.Handled {

    record Reply(ReplyInfo replyInfo) implements AgentOutcome {
    }

    record Handled(String reason) implements AgentOutcome {
        public Handled() {
            this("handled");
        }
    }
}
