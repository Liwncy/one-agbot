package me.liwncy.agbot.kernel.api.session;

/**
 * sessionKey → SnailAI conversationId。
 */
public interface ConversationMapper {

    String resolveConversationId(String sessionKey);
}
