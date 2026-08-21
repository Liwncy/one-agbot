package me.liwncy.agbot.kernel.chatlog;

import me.liwncy.agbot.kernel.api.message.MsgInfo;

/**
 * 与入库一致的 session_id：{@code group:群id} / {@code user:wxid}。
 */
public final class ChatLogSessions {

    private ChatLogSessions() {
    }

    public static String of(MsgInfo msg) {
        if (msg == null) {
            return "user:unknown";
        }
        return of(msg.userId(), msg.groupId());
    }

    public static String of(String userId, String groupId) {
        boolean group = groupId != null && !groupId.isBlank() && !"0".equals(groupId);
        if (group) {
            return "group:" + groupId.trim();
        }
        if (userId == null || userId.isBlank()) {
            return "user:unknown";
        }
        return "user:" + userId.trim();
    }
}
