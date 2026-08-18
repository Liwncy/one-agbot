package me.liwncy.agbot.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本里未标明类型、又不是图/视频的 http(s)，按链接卡片发出。
 */
final class AgentOutboundPageLinks {
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_URL = Pattern.compile(
            "https?://[^\\s<>\"'\\]\\)\\u4e00-\\u9fff]+",
            Pattern.CASE_INSENSITIVE);

    private AgentOutboundPageLinks() {
    }

    record Split(List<String> pageUrls, String remainingText) {
        boolean hasPages() {
            return pageUrls != null && !pageUrls.isEmpty();
        }
    }

    static Split split(String text) {
        if (text == null || text.isBlank()) {
            return new Split(List.of(), text == null ? "" : text);
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collect(MARKDOWN_LINK, text, urls, true);
        collect(BARE_URL, text, urls, false);

        if (urls.isEmpty()) {
            return new Split(List.of(), text);
        }

        String remaining = text;
        remaining = MARKDOWN_LINK.matcher(remaining).replaceAll(" ");
        for (String url : urls) {
            remaining = remaining.replace(url, " ");
        }
        remaining = remaining.replaceAll("[\\t ]+", " ").replaceAll(" ?\\n ?", "\n").trim();
        return new Split(List.copyOf(urls), remaining);
    }

    static boolean looksLikeHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    static boolean looksLikePageUrl(String url) {
        if (!looksLikeHttpUrl(url)) {
            return false;
        }
        return !AgentOutboundImages.looksLikeImageUrl(url) && !AgentOutboundVideos.looksLikeVideoUrl(url);
    }

    private static void collect(Pattern pattern, String text, LinkedHashSet<String> out, boolean group1) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String raw = group1 ? m.group(1) : m.group();
            String url = sanitizeUrl(raw);
            if (url != null && looksLikePageUrl(url)) {
                out.add(url);
            }
        }
    }

    private static String sanitizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(";") || url.endsWith("。")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("<") && url.endsWith(">")) {
            url = url.substring(1, url.length() - 1);
        }
        return url.isBlank() ? null : url;
    }
}
