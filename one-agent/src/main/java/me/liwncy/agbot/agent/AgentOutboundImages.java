package me.liwncy.agbot.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 文本片段中识别图片 URL，供流式分片出站使用。
 * <p>cf-mcp-tools 短链 id 固定 20 位 hex（见 image-host newImageId）。</p>
 */
final class AgentOutboundImages {
    /** 与 Worker 生成的短链 id 长度一致。 */
    static final int SHORT_IMAGE_ID_LEN = 20;

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
    private static final Pattern SHORT_IMAGE_PATH = Pattern.compile(
            "/i/([a-f0-9]+)(?:$|[?#])",
            Pattern.CASE_INSENSITIVE);

    private AgentOutboundImages() {
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

        String remaining = MARKDOWN_IMAGE.matcher(text).replaceAll(" ");
        for (String url : urls) {
            remaining = remaining.replace(url, " ");
        }
        remaining = remaining.replaceAll("[\\t ]+", " ").replaceAll(" ?\\n ?", "\n").trim();
        remaining = collapseJsonShell(remaining);
        return new Split(List.copyOf(urls), remaining);
    }

    static boolean looksLikeImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return false;
        }
        Integer shortIdLen = shortImageIdLength(lower);
        if (shortIdLen != null) {
            return shortIdLen == SHORT_IMAGE_ID_LEN;
        }
        if (IMAGE_EXT.matcher(lower).find()) {
            return true;
        }
        return lower.contains("siliconflow") && lower.contains("/temporary/");
    }

    /**
     * 流式场景：短链必须满 20 位；SiliconFlow 签名链必须等空白收尾；带扩展名可在流末切开。
     */
    static boolean looksCompleteImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return false;
        }
        if (lower.contains("siliconflow") && lower.contains("/temporary/")) {
            // 预签名参数会边流边变长，必须等空白/标点收尾
            return false;
        }
        Integer shortIdLen = shortImageIdLength(lower);
        if (shortIdLen != null) {
            return shortIdLen == SHORT_IMAGE_ID_LEN;
        }
        if (!looksLikeImageUrl(url)) {
            return false;
        }
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        return path.matches(".*\\.(?:png|jpe?g|gif|webp|bmp)$");
    }

    /** @return 短链 id 长度；非短链返回 null */
    static Integer shortImageIdLength(String urlLower) {
        Matcher m = SHORT_IMAGE_PATH.matcher(urlLower);
        if (!m.find()) {
            return null;
        }
        return m.group(1).length();
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
        return drawToolJson ? "" : t;
    }
}
