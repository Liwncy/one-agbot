package me.liwncy.agbot.adapter.golem.inbound;

import com.fasterxml.jackson.core.type.TypeReference;
import me.liwncy.agbot.common.json.JsonUtils;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MediaForm;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import me.liwncy.agbot.kernel.chatlog.ChatLogSessions;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 引用图/表情解析不到定位符时，按 svrid 找回原 inbound 的 path/cdn。
 */
public class GolemQuoteMediaEnricher {
    private static final Logger log = LoggerFactory.getLogger(GolemQuoteMediaEnricher.class);

    private static final List<String> COPY_KEYS = List.of(
            ChannelExtraKeys.MEDIA_URL,
            ChannelExtraKeys.MEDIA_FORM,
            ChannelExtraKeys.MEDIA_PLATFORM_ID,
            ChannelExtraKeys.MEDIA_MIME,
            ChannelExtraKeys.MD5,
            ChannelExtraKeys.THUMB,
            ChannelExtraKeys.MEDIA_SIZE,
            "aeskey",
            "thumbAeskey",
            "emojiUrlCandidates",
            "length"
    );

    private final ChatLogService chatLog;

    public GolemQuoteMediaEnricher(ChatLogService chatLog) {
        this.chatLog = chatLog;
    }

    public MsgInfo enrich(MsgInfo msg) {
        if (msg == null || chatLog == null) {
            return msg;
        }
        String quoteType = string(msg.extra() == null ? null : msg.extra().get(ChannelExtraKeys.QUOTE_MSG_TYPE));
        if (!MsgType.EMOJI.equals(quoteType) && !MsgType.IMAGE.equals(quoteType)) {
            return msg;
        }
        if (hasUsableMedia(msg)) {
            return msg;
        }
        String svrid = msg.replyToMsgId();
        if (svrid == null || svrid.isBlank()) {
            return msg;
        }
        List<ChatMessage> rows;
        try {
            rows = chatLog.listInboundBySvrid(
                    msg.accountId(),
                    ChatLogSessions.of(msg.userId(), msg.groupId()),
                    svrid);
        } catch (Exception e) {
            log.warn("Golem quote media chat-log lookup failed svrid={}: {}", svrid, e.toString());
            return msg;
        }
        ChatMessage hit = pick(rows, quoteType);
        if (hit == null) {
            return msg;
        }
        Map<String, Object> stored = parseExtra(hit.getAdapterExtra());
        Map<String, Object> extra = new HashMap<>(msg.extra() == null ? Map.of() : msg.extra());
        boolean copied = false;
        for (String key : COPY_KEYS) {
            if (blank(extra.get(key)) && !blank(stored.get(key))) {
                extra.put(key, stored.get(key));
                copied = true;
            }
        }
        String storedPath = firstNonBlank(
                string(stored.get(ChannelExtraKeys.MEDIA_URL)),
                looksLikeLocalFile(hit.getContentText()) ? hit.getContentText() : "");
        String path = msg.path();
        if ((path == null || path.isBlank()) && !storedPath.isBlank()) {
            if (looksLikeLocalFile(storedPath) && !Files.isRegularFile(Path.of(storedPath))) {
                extra.remove(ChannelExtraKeys.MEDIA_FORM);
                extra.remove(ChannelExtraKeys.MEDIA_URL);
            } else {
                path = storedPath;
                copied = true;
            }
        }
        if (!copied) {
            return msg;
        }
        log.info("Golem quote media enriched from chat-log quoteType={} svrid={} path={}",
                quoteType, svrid, preview(path));
        return new MsgInfo(
                msg.platform(),
                msg.accountId(),
                msg.userId(),
                msg.userName(),
                msg.groupId(),
                msg.groupName(),
                msg.msg(),
                msg.msgId(),
                msg.fromType(),
                msg.msgType(),
                path,
                msg.replyToMsgId(),
                msg.createTime(),
                Map.copyOf(extra)
        );
    }

    private static ChatMessage pick(List<ChatMessage> rows, String quoteType) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        ChatMessage typed = null;
        ChatMessage any = null;
        for (ChatMessage row : rows) {
            Map<String, Object> extra = parseExtra(row.getAdapterExtra());
            if (!hasStoredMedia(row, extra)) {
                continue;
            }
            if (any == null) {
                any = row;
            }
            if (quoteType.equalsIgnoreCase(row.getMsgType() == null ? "" : row.getMsgType().trim())) {
                typed = row;
                break;
            }
        }
        return typed != null ? typed : any;
    }

    private static boolean hasStoredMedia(ChatMessage row, Map<String, Object> extra) {
        if (!string(extra.get(ChannelExtraKeys.MEDIA_URL)).isBlank()
                || !string(extra.get(ChannelExtraKeys.MEDIA_PLATFORM_ID)).isBlank()
                || !string(extra.get("emojiUrlCandidates")).isBlank()
                || !string(extra.get("aeskey")).isBlank()
                || !string(extra.get(ChannelExtraKeys.THUMB)).isBlank()) {
            return true;
        }
        return looksLikeLocalFile(row.getContentText()) || MediaRef.from(row.getContentText(), extra) != null;
    }

    private static boolean hasUsableMedia(MsgInfo msg) {
        MediaRef ref = MediaRef.fromMsg(msg);
        if (ref != null && ref.usableForFetch()) {
            if (ref.form() == MediaForm.FILE && !Files.isRegularFile(Path.of(ref.path()))) {
                return false;
            }
            return true;
        }
        String joined = string(msg.extra() == null ? null : msg.extra().get("emojiUrlCandidates"));
        if (!joined.isBlank()) {
            for (String part : joined.split("\\|")) {
                if (looksLikeHttp(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> parseExtra(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> extra = JsonUtils.fromJson(json, new TypeReference<>() {
            });
            return extra == null ? Map.of() : extra;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static boolean looksLikeLocalFile(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return false;
        }
        return lower.startsWith("file:") || text.contains("\\") || text.startsWith("/");
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }
}
