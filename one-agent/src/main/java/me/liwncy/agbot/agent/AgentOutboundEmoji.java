package me.liwncy.agbot.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 文本识别 {@code emoji:<32hex>}[ 可选 url] 出站协议。
 */
final class AgentOutboundEmoji {
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)emoji:([0-9a-f]{32})(?:[ \\t]+(https?://\\S+))?");

    private AgentOutboundEmoji() {
    }

    record Ref(String md5, String imageUrl) {
    }

    record Split(List<Ref> emojis, String remainingText) {
        boolean hasEmojis() {
            return emojis != null && !emojis.isEmpty();
        }
    }

    static boolean looksLikeEmojiLine(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        Matcher m = TOKEN.matcher(trimmed);
        return m.matches();
    }

    static Ref parseLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = TOKEN.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String md5 = m.group(1).toLowerCase(Locale.ROOT);
        String url = m.group(2) == null ? "" : stripTrailingPunct(m.group(2));
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
            String url = m.group(2) == null ? "" : stripTrailingPunct(m.group(2));
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

    private static String stripTrailingPunct(String url) {
        String value = url.trim();
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
