package me.liwncy.agbot.kernel.api.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一出站消息（对齐 Bncr replyInfo，类型上限见 {@link MsgType}）。
 */
public record ReplyInfo(
        String type,
        String msg,
        String path,
        String userId,
        String groupId,
        String toMsgId,
        String accountId,
        String remind,
        String title,
        String url,
        Map<String, Object> extra
) {
    public ReplyInfo {
        type = MsgType.normalize(type);
        if (extra == null) {
            extra = Collections.emptyMap();
        }
    }

    public static ReplyInfo text(String msg, MsgInfo inbound) {
        return merge(of(MsgType.TEXT, msg, null, null, null, null), inbound);
    }

    public static ReplyInfo image(String path, MsgInfo inbound) {
        return merge(of(MsgType.IMAGE, null, path, null, null, null), inbound);
    }

    public static ReplyInfo video(String path, MsgInfo inbound) {
        return merge(of(MsgType.VIDEO, null, path, null, null, null), inbound);
    }

    public static ReplyInfo audio(String path, MsgInfo inbound) {
        return merge(of(MsgType.AUDIO, null, path, null, null, null), inbound);
    }

    public static ReplyInfo file(String path, MsgInfo inbound) {
        return merge(of(MsgType.FILE, null, path, null, null, null), inbound);
    }

    public static ReplyInfo emoji(String pathOrMd5, MsgInfo inbound) {
        Map<String, Object> extra = new HashMap<>();
        if (pathOrMd5 != null && !pathOrMd5.contains("://") && pathOrMd5.matches("[0-9a-fA-F]{32}")) {
            extra.put(ChannelExtraKeys.MD5, pathOrMd5);
            return merge(of(MsgType.EMOJI, null, null, null, null, null, extra), inbound);
        }
        return merge(of(MsgType.EMOJI, null, pathOrMd5, null, null, null), inbound);
    }

    public static ReplyInfo link(String title, String desc, String url, MsgInfo inbound) {
        return link(title, desc, url, null, inbound);
    }

    public static ReplyInfo link(String title, String desc, String url, String thumb, MsgInfo inbound) {
        Map<String, Object> extra = new HashMap<>();
        extra.put(ChannelExtraKeys.THUMB, thumb == null ? "" : thumb);
        return merge(of(MsgType.LINK, desc, null, null, title, url, extra), inbound);
    }

    public static ReplyInfo app(int appType, String xml, MsgInfo inbound) {
        Map<String, Object> extra = new HashMap<>();
        extra.put(ChannelExtraKeys.APP_TYPE, appType);
        return merge(of(MsgType.APP, xml, null, null, null, null, extra), inbound);
    }

    public static ReplyInfo of(String type, String msg, String path,
                               String remind, String title, String url) {
        return of(type, msg, path, remind, title, url, Map.of());
    }

    public static ReplyInfo of(String type, String msg, String path,
                               String remind, String title, String url,
                               Map<String, Object> extra) {
        return new ReplyInfo(type, msg, path, null, null, null, null, remind, title, url, extra);
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
                reply.remind(),
                reply.title(),
                reply.url(),
                extra
        );
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
