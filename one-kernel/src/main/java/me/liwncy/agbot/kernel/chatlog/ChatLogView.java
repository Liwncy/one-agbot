package me.liwncy.agbot.kernel.chatlog;

import com.fasterxml.jackson.core.type.TypeReference;
import me.liwncy.agbot.common.json.JsonUtils;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给模型看的聊天记录正文：表情/图把 extra 里的 md5、http 地址拼上，不把本地盘符路径塞出去。
 */
public final class ChatLogView {

    private static final Pattern MD5_IN_JSON = Pattern.compile("\"md5\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern URL_IN_JSON = Pattern.compile("\"(?:mediaUrl|url)\"\\s*:\\s*\"(https?://[^\"\\\\]+)\"");

    private ChatLogView() {
    }

    public static String body(ChatMessage row, int clip) {
        if (row == null) {
            return "";
        }
        String type = row.getMsgType() == null ? "" : row.getMsgType().trim();
        String text = clip(row.getContentText(), clip);
        if (looksLikeLocalFile(text)) {
            text = "";
        }
        if (text.isBlank()) {
            text = type.isBlank() ? "[消息]" : "[" + type + "]";
        }
        Map<String, Object> extra = extra(row);
        String md5 = string(extra.get(ChannelExtraKeys.MD5));
        String url = httpUrl(extra);
        StringBuilder sb = new StringBuilder(text);
        if (!md5.isBlank() && !containsToken(text, "md5=" + md5)) {
            sb.append(" md5=").append(md5);
        }
        if (!url.isBlank() && !containsToken(text, url)) {
            sb.append(" url=").append(url);
        }
        return sb.toString().trim();
    }

    public static Map<String, Object> extra(ChatMessage row) {
        if (row == null) {
            return Map.of();
        }
        return parseExtra(row.getAdapterExtra());
    }

    public static boolean looksLikeLocalFile(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return false;
        }
        return lower.startsWith("file:")
                || text.contains("\\")
                || text.startsWith("/");
    }

    static Map<String, Object> parseExtra(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> extra = JsonUtils.fromJson(json, new TypeReference<>() {
            });
            return extra == null ? Map.of() : extra;
        } catch (Exception ignored) {
            return recoverExtra(json);
        }
    }

    /** extra JSON 被截断时仍尽量抠出 md5 / http 地址。 */
    private static Map<String, Object> recoverExtra(String json) {
        Map<String, Object> extra = new LinkedHashMap<>();
        Matcher md5 = MD5_IN_JSON.matcher(json);
        if (md5.find()) {
            extra.put(ChannelExtraKeys.MD5, md5.group(1));
        }
        Matcher url = URL_IN_JSON.matcher(json);
        if (url.find()) {
            extra.put(ChannelExtraKeys.MEDIA_URL, url.group(1));
        }
        return extra.isEmpty() ? Map.of() : extra;
    }

    private static String httpUrl(Map<String, Object> extra) {
        String direct = firstHttp(
                string(extra.get(ChannelExtraKeys.MEDIA_URL)),
                string(extra.get("url")),
                string(extra.get("emoji_url")));
        if (!direct.isBlank()) {
            return direct;
        }
        String candidates = string(extra.get("emojiUrlCandidates"));
        if (candidates.isBlank()) {
            return "";
        }
        for (String part : candidates.split("\\|")) {
            String hit = firstHttp(part);
            if (!hit.isBlank()) {
                return hit;
            }
        }
        return "";
    }

    private static String firstHttp(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (looksLikeHttp(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean containsToken(String text, String token) {
        return text != null && token != null && !token.isBlank() && text.contains(token);
    }

    private static String clip(String raw, int max) {
        String text = raw == null ? "" : raw.replace('\n', ' ').trim();
        if (max < 1 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
