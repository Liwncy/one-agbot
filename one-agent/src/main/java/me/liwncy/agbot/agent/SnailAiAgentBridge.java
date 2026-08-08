package me.liwncy.agbot.agent;

import me.liwncy.agbot.agent.config.AgbotAgentProperties;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.agent.AgentOutcome;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import me.liwncy.agbot.kernel.api.session.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 默认 AgentBridge：OpenAPI sync chat。
 */
public class SnailAiAgentBridge implements AgentBridge {
    private static final Logger log = LoggerFactory.getLogger(SnailAiAgentBridge.class);

    private final SnailAiOpenApiClient client;
    private final ConversationMapper conversationMapper;
    private final AgbotAgentProperties properties;

    public SnailAiAgentBridge(SnailAiOpenApiClient client,
                              ConversationMapper conversationMapper,
                              AgbotAgentProperties properties) {
        this.client = client;
        this.conversationMapper = conversationMapper;
        this.properties = properties;
    }

    @Override
    public CompletableFuture<AgentOutcome> handle(MsgInfo msgInfo) {
        // MVP：同步 chat/sync。asyncHandled=true 时先返回 Handled，后台仍执行（出站需后续回调扩展）。
        if (properties.isAsyncHandled()) {
            CompletableFuture.runAsync(() -> {
                try {
                    doHandle(msgInfo);
                } catch (Exception e) {
                    log.error("Async agent handle failed", e);
                }
            });
            return CompletableFuture.completedFuture(new AgentOutcome.Handled("async"));
        }
        return CompletableFuture.supplyAsync(() -> doHandle(msgInfo));
    }

    private AgentOutcome doHandle(MsgInfo msgInfo) {
        String externalId = SessionKeys.externalUserId(msgInfo);
        String openId = client.ensureOpenId(externalId, msgInfo.userName());
        String conversationId = conversationMapper.resolveConversationId(SessionKeys.of(msgInfo));
        log.debug("Agent chat openId={} conversationId={}", openId, conversationId);
        String answer = client.chatSync(
                properties.getDefaultAgentId(),
                openId,
                conversationId,
                msgInfo.msg() == null ? "" : msgInfo.msg()
        );
        return new AgentOutcome.Reply(ReplyInfo.text(answer, msgInfo));
    }
}
