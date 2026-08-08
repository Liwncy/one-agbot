package me.liwncy.agbot.kernel.api.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一出站消息（对齐 Bncr replyInfo）。
 */
public record ReplyInfo(
        String type,
        String msg,
        String path,
        String userId,
        String groupId,
        String toMsgId,
        String accountId,
        Map<String, Object> extra
) {
    public ReplyInfo {
        if (type == null || type.isBlank()) {
            type = MsgType.TEXT;
        }
        if (extra == null) {
            extra = Collections.emptyMap();
        }
    }

    public static ReplyInfo text(String msg, MsgInfo inbound) {
        return merge(new ReplyInfo(MsgType.TEXT, msg, null, null, null, null, null, Map.of()), inbound);
    }

    /**
     * Bncr 1.0.8：缺省字段从入站 msgInfo 合并。
     */
    public static ReplyInfo merge(ReplyInfo reply, MsgInfo inbound) {
        if (inbound == null) {
            return reply;
        }
        Map<String, Object> extra = new HashMap<>(inbound.extra());
        if (reply.extra() != null) {
            extra.putAll(reply.extra());
        }
        return new ReplyInfo(
                reply.type() != null ? reply.type() : MsgType.TEXT,
                reply.msg(),
                reply.path(),
                blankTo(reply.userId(), inbound.userId()),
                blankTo(reply.groupId(), inbound.groupId()),
                blankTo(reply.toMsgId(), inbound.msgId()),
                blankTo(reply.accountId(), inbound.accountId()),
                extra
        );
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
