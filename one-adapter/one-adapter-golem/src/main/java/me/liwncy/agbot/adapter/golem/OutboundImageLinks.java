package me.liwncy.agbot.adapter.golem;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 文本回复中识别可出站的图片 URL（短链 /i/、常见图片后缀、SiliconFlow 临时链、markdown 图）。
 */
final class OutboundImageLinks {
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_URL = Pattern.compile(
            "https?://[^\\s<>\"'\\]\\)\\u4e00-\\u9fff]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_URL_FIELD = Pattern.compile(
            "\"url\"\\s*:\\s*\"(https?://[^\"\\s]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_EXT = Pattern.compile(
            "\\.(?:png|jpe?g|gif|webp|bmp)(?:$|[?#])",
            Pattern.CASE_INSENSITIVE);
    /** 与 cf-mcp-tools 短链 id 长度一致（20 hex）。 */
    private static final Pattern SHORT_IMAGE_PATH = Pattern.compile(
            "/i/[a-f0-9]{20}(?:$|[?#])",
            Pattern.CASE_INSENSITIVE);

    private OutboundImageLinks() {
    }

    record Split(List<String> imageUrls, String remainingText) {
        boolean hasImages() {
            return imageUrls != null && !imageUrls.isEmpty();
        }
    }

    static Split split(String text) {
        if (text == null || text.isBlank()) {
            return new Split(List.of(), text == null ? "" : text);
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collect(MARKDOWN_IMAGE, text, urls, true);
        collect(JSON_URL_FIELD, text, urls, true);
        collect(BARE_URL, text, urls, false);

        if (urls.isEmpty()) {
            return new Split(List.of(), text);
        }

        String remaining = text;
        remaining = MARKDOWN_IMAGE.matcher(remaining).replaceAll(" ");
        for (String url : urls) {
            remaining = remaining.replace(url, " ");
        }
        remaining = remaining.replaceAll("[\\t ]+", " ").replaceAll(" ?\\n ?", "\n").trim();
        remaining = collapseJsonShell(remaining);

        return new Split(List.copyOf(urls), remaining);
    }

    private static void collect(Pattern pattern, String text, LinkedHashSet<String> out, boolean group1) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String raw = group1 ? m.group(1) : m.group();
            String url = sanitizeUrl(raw);
            if (url != null && looksLikeImageUrl(url)) {
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

    static boolean looksLikeImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return false;
        }
        if (SHORT_IMAGE_PATH.matcher(lower).find()) {
            return true;
        }
        if (IMAGE_EXT.matcher(lower).find()) {
            return true;
        }
        // SiliconFlow temporary signed objects are usually real images even as octet-stream.
        return lower.contains("siliconflow") && lower.contains("/temporary/");
    }

    /**
     * Agent 有时把工具 JSON 整段贴出；抽完 url 后若只剩绘图工具结果壳则不再当文字发出。
     */
    private static String collapseJsonShell(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.trim();
        if (!(t.startsWith("{") && t.endsWith("}"))) {
            return t;
        }
        boolean drawToolJson = t.contains("\"provider\"")
                || t.contains("\"quality\"")
                || (t.contains("\"prompt\"") && t.contains("\"scale\""));
        if (drawToolJson) {
            return "";
        }
        return t;
    }
}
