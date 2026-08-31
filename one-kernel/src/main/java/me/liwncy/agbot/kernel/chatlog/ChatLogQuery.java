package me.liwncy.agbot.kernel.chatlog;

import java.time.LocalDateTime;

/**
 * 聊天记录查询条件。{@code sessionId} 形如 {@code group:xxx@chatroom} / {@code user:wxid}。
 */
public record ChatLogQuery(
        String accountId,
        String sessionId,
        String platform,
        String direction,
        LocalDateTime since,
        LocalDateTime until,
        String senderId,
        String senderName,
        String keyword,
        String msgType,
        Long beforeId,
        Long afterId,
        int limit
) {
}
