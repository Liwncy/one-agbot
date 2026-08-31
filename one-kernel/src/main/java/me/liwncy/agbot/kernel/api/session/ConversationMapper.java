package me.liwncy.agbot.kernel.api.session;

/**
 * sessionKey → SnailAI conversationId。
 */
public interface ConversationMapper {

    String resolveConversationId(String sessionKey);

    /**
     * 丢掉当前映射，下次 {@link #resolveConversationId} 会新开对话。
     * 切人设时用，避免旧口气残留在 SnailAI 历史里。
     */
    default void reset(String sessionKey) {
    }
}
