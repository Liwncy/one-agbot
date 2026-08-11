package me.liwncy.agbot.adapter.golem;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 文本回复中识别可出站的视频 URL（常见视频后缀）。
 */
final class OutboundVideoLinks {
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_URL = Pattern.compile(
            "https?://[^\\s<>\"'\\]\\)\\u4e00-\\u9fff]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_URL_FIELD = Pattern.compile(
            "\"(?:url|videoUrl|video_url)\"\\s*:\\s*\"(https?://[^\"\\s]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_EXT = Pattern.compile(
            "\\.(?:mp4|mov|webm|m4v|mkv)(?:$|[?#])",
            Pattern.CASE_INSENSITIVE);

    private OutboundVideoLinks() {
    }

    record Split(List<String> videoUrls, String remainingText) {
        boolean hasVideos() {
            return videoUrls != null && !videoUrls.isEmpty();
        }
    }

    static Split split(String text) {
        if (text == null || text.isBlank()) {
            return new Split(List.of(), text == null ? "" : text);
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collect(MARKDOWN_LINK, text, urls, true);
        collect(JSON_URL_FIELD, text, urls, true);
        collect(BARE_URL, text, urls, false);

        if (urls.isEmpty()) {
            return new Split(List.of(), text);
        }

        String remaining = text;
        for (String url : urls) {
            remaining = remaining.replace(url, " ");
        }
        remaining = remaining.replaceAll("[\\t ]+", " ").replaceAll(" ?\\n ?", "\n").trim();
        return new Split(List.copyOf(urls), remaining);
    }

    static boolean looksLikeVideoUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return false;
        }
        if (OutboundImageLinks.looksLikeImageUrl(url)) {
            return false;
        }
        return VIDEO_EXT.matcher(lower).find();
    }

    private static void collect(Pattern pattern, String text, LinkedHashSet<String> out, boolean group1) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String raw = group1 ? m.group(1) : m.group();
            String url = sanitizeUrl(raw);
            if (url != null && looksLikeVideoUrl(url)) {
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
