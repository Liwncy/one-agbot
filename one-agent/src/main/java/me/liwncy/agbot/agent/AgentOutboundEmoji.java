package me.liwncy.agbot.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 文本识别 {@code emoji:<32hex>|url} 出站协议（旧空格分隔仍认）。
 */
final class AgentOutboundEmoji {
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)emoji:([0-9a-f]{32})(?:[ \\t]*\\|[ \\t]*(https?://\\S+)|[ \\t]+(https?://\\S+))?");
    private static final Pattern LINE = Pattern.compile(
            "(?i)^emoji:([0-9a-f]{32})(?:\\|([^|]*))?(?:[ \\t]+(https?://\\S+))?$");

    private AgentOutboundEmoji() {
    }

    record Ref(String md5, String imageUrl) {
        String toLine() {
            if (imageUrl == null || imageUrl.isBlank()) {
                return "emoji:" + md5;
            }
            return "emoji:" + md5 + "|" + imageUrl;
        }
    }

    record Split(List<Ref> emojis, String remainingText) {
        boolean hasEmojis() {
            return emojis != null && !emojis.isEmpty();
        }
    }

    static boolean looksLikeEmojiLine(String text) {
        return parseLine(text) != null;
    }

    static Ref parseLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = LINE.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String md5 = m.group(1).toLowerCase(Locale.ROOT);
        String piped = m.group(2) == null ? "" : stripTrailingPunct(m.group(2).trim());
        String spaced = m.group(3) == null ? "" : stripTrailingPunct(m.group(3));
        String url = looksLikeHttp(piped) ? piped : spaced;
        return new Ref(md5, url);
    }

    static Split split(String text) {
        if (text == null || text.isBlank()) {
            return new Split(List.of(), text == null ? "" : text);
        }
        List<Ref> refs = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String md5 = m.group(1).toLowerCase(Locale.ROOT);
            String piped = m.group(2) == null ? "" : stripTrailingPunct(m.group(2));
            String spaced = m.group(3) == null ? "" : stripTrailingPunct(m.group(3));
            String url = looksLikeHttp(piped) ? piped : spaced;
            refs.add(new Ref(md5, url));
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        if (refs.isEmpty()) {
            return new Split(List.of(), text);
        }
        String remaining = sb.toString()
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .trim();
        return new Split(List.copyOf(refs), remaining);
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String stripTrailingPunct(String url) {
        String value = url == null ? "" : url.trim();
        while (!value.isEmpty()) {
            char c = value.charAt(value.length() - 1);
            if (c == ')' || c == ']' || c == '"' || c == '\''
                    || c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == ',' || c == '，') {
                value = value.substring(0, value.length() - 1);
                continue;
            }
            break;
        }
        return value;
    }
}
