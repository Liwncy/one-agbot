package me.liwncy.agbot.adapter.golem.inbound;

import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MediaForm;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 将入站媒体从 PLATFORM 升级为 FILE/URL/BASE64（下载逻辑仅在适配器内）。
 */
public class GolemMediaResolver {
    private static final Logger log = LoggerFactory.getLogger(GolemMediaResolver.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final GolemApiClient apiClient;
    private final GolemProperties properties;

    public GolemMediaResolver(GolemApiClient apiClient, GolemProperties properties) {
        this.apiClient = apiClient;
        this.properties = properties;
    }

    public MsgInfo resolve(MsgInfo msg) {
        if (msg == null || !properties.isMediaResolveEnabled()) {
            return msg;
        }
        // 引用图/视频/表情：顶层 msgType 常为 TEXT，按 quoteMsgType 走下载
        String type = effectiveMediaType(msg);
        if (!isMediaType(type)) {
            return msg;
        }
        MediaRef current = MediaRef.fromMsg(msg);
        // FILE/BASE64 已可用；表情/微信图床 URL 一律落盘（域名不固定，Agent 直拉常 403）
        if (current != null && current.usableForFetch()
                && !(current.form() == MediaForm.URL && needsLocalMaterialize(current.path(), type))) {
            return msg;
        }
        try {
            ResolvedMedia resolved = download(msg, type, current);
            if (resolved == null || resolved.bytes() == null || resolved.bytes().length == 0) {
                log.warn("Golem media resolve empty type={} quoteType={} msgId={} locator={}",
                        msg.msgType(), type, msg.msgId(),
                        preview(current == null ? msg.path() : firstNonBlank(current.path(), current.platformId())));
                return msg;
            }
            return rewrite(msg, resolved);
        } catch (Exception e) {
            log.warn("Golem media resolve failed type={} quoteType={} msgId={}: {}",
                    msg.msgType(), type, msg.msgId(), e.getMessage());
            return msg;
        }
    }

    private ResolvedMedia download(MsgInfo msg, String type, MediaRef current) {
        Map<String, Object> extra = msg.extra() == null ? Map.of() : msg.extra();
        String aesKey = string(extra.get("aeskey"));
        String httpUrl = firstNonBlank(
                current != null && current.form() == MediaForm.URL ? current.path() : null,
                looksLikeHttp(msg.path()) ? msg.path() : null,
                looksLikeHttp(string(extra.get(ChannelExtraKeys.MEDIA_URL)))
                        ? string(extra.get(ChannelExtraKeys.MEDIA_URL)) : null
        );
        String cdnId = firstNonBlank(
                current == null ? null : current.platformId(),
                current != null && current.form() != MediaForm.URL ? current.path() : null,
                !looksLikeHttp(msg.path()) ? msg.path() : null,
                string(extra.get(ChannelExtraKeys.MEDIA_PLATFORM_ID))
        );
        // MD5 不能当 CDN id 下载
        if (!cdnId.isBlank() && cdnId.equalsIgnoreCase(string(extra.get(ChannelExtraKeys.MD5)))) {
            cdnId = "";
        }
        String thumb = string(extra.get(ChannelExtraKeys.THUMB));
        String thumbAes = firstNonBlank(string(extra.get("thumbAeskey")), aesKey);

        // 0) 表情/图片 HTTP 直链（主链 + 备用链）——先落到本地，避免 Agent 被防盗链拦
        if (MsgType.EMOJI.equals(type) || MsgType.IMAGE.equals(type)) {
            for (String candidate : emojiHttpCandidates(httpUrl, extra)) {
                try {
                    byte[] bytes = httpDownload(candidate);
                    if (bytes != null && bytes.length > 64 && looksLikeImageBytes(bytes)) {
                        String mime = sniffImageMime(bytes, string(extra.get(ChannelExtraKeys.MEDIA_MIME)));
                        return new ResolvedMedia(bytes, guessExt(type, mime), mime);
                    }
                } catch (Exception e) {
                    log.debug("HTTP media download failed url={}: {}", preview(candidate), e.getMessage());
                }
            }
        }

        // 1) Golem CDN 下载（图/视频/表情）——引用消息主要靠 aeskey + 非 http 的 id
        if (!cdnId.isBlank() && !aesKey.isBlank() && !looksLikeHttp(cdnId)) {
            try {
                byte[] bytes = switch (type) {
                    case MsgType.IMAGE, MsgType.EMOJI -> apiClient.cdnDownloadImage(cdnId, aesKey);
                    case MsgType.VIDEO -> apiClient.cdnDownloadVideo(cdnId, aesKey);
                    default -> null;
                };
                if (bytes != null && bytes.length > 0) {
                    return new ResolvedMedia(bytes, guessExt(type, string(extra.get(ChannelExtraKeys.MEDIA_MIME))),
                            guessMime(type, string(extra.get(ChannelExtraKeys.MEDIA_MIME))));
                }
            } catch (Exception e) {
                log.debug("CDN download failed, fallback msg download: {}", e.getMessage());
            }
            // 视频可尝试封面（引用视频给 Agent 看封面更有用）
            if (MsgType.VIDEO.equals(type) && !thumb.isBlank() && !thumbAes.isBlank()) {
                try {
                    byte[] cover = apiClient.cdnDownloadImage(thumb, thumbAes);
                    if (cover.length > 0) {
                        return new ResolvedMedia(cover, "jpg", "image/jpeg");
                    }
                } catch (Exception ignored) {
                    // continue
                }
            }
        }

        // 2) 按消息 id 下载；引用媒体优先 replyToMsgId(svrid)，勿用外层引用气泡自己的 msgId
        boolean quoteMedia = isMediaType(string(extra.get(ChannelExtraKeys.QUOTE_MSG_TYPE)));
        long[] ids = quoteMedia ? idsFromReplyTo(msg.replyToMsgId()) : null;
        if (ids == null) {
            ids = parsePackedIds(msg.msgId());
        }
        if (ids == null) {
            ids = idsFromReplyTo(msg.replyToMsgId());
        }
        long size = parseLong(extra.get("length"), parseLong(extra.get(ChannelExtraKeys.MEDIA_SIZE), 0L));
        // 表情引用经常缺 length，仍尝试按 svrid 拉图
        boolean tryWithoutSize = MsgType.EMOJI.equals(type) || MsgType.IMAGE.equals(type);
        if (ids != null && (size > 0 || tryWithoutSize)) {
            long downloadSize = size > 0 ? size : 2L * 1024 * 1024;
            try {
                // 引用媒体的 sender 是被引用消息作者，不是当前说话人
                String mediaSender = quoteMedia
                        ? firstNonBlank(string(extra.get(ChannelExtraKeys.QUOTE_FROM)), msg.userId())
                        : msg.userId();
                byte[] bytes = switch (type) {
                    case MsgType.IMAGE, MsgType.EMOJI -> apiClient.downloadImageByMsg(
                            ids[1], ids[0], mediaSender, downloadSize);
                    case MsgType.VIDEO -> apiClient.downloadVideoByMsg(ids[1], ids[0], size);
                    case MsgType.AUDIO -> apiClient.downloadVoiceByMsg(
                            ids[1], ids[0],
                            parseLong(extra.get("bufferId"), 0L),
                            size,
                            msg.isPrivateChat() ? "" : msg.groupId()
                    );
                    default -> null;
                };
                if (bytes != null && bytes.length > 0) {
                    return new ResolvedMedia(bytes, guessExt(type, null), guessMime(type, null));
                }
            } catch (Exception e) {
                log.debug("Message download failed: {}", e.getMessage());
            }
        }
        return null;
    }

    /** 表情一律落盘；图片遇到微信/QQ 图床也落盘（Agent 直拉常 403）。 */
    private static boolean needsLocalMaterialize(String url, String type) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (MsgType.EMOJI.equals(type)) {
            return true;
        }
        if (!MsgType.IMAGE.equals(type)) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("qpic.cn")
                || u.contains("qq.com")
                || u.contains("weixin.qq.com")
                || u.contains("wx.qlogo.cn")
                || u.contains("vweixinf")
                || u.contains("emoji")
                || u.contains("snsvideo")
                || u.contains("wxsnsdy");
    }

    private static List<String> emojiHttpCandidates(String primary, Map<String, Object> extra) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (primary != null && looksLikeHttp(primary)) {
            out.add(primary.trim());
        }
        String joined = string(extra.get("emojiUrlCandidates"));
        if (!joined.isBlank()) {
            for (String part : joined.split("\\|")) {
                if (looksLikeHttp(part)) {
                    out.add(part.trim());
                }
            }
        }
        for (String key : List.of(
                ChannelExtraKeys.MEDIA_URL, "emoji_url", "cdnurl", "encrypturl", "externurl", "thumburl")) {
            String v = string(extra.get(key));
            if (looksLikeHttp(v)) {
                out.add(v.trim());
            }
        }
        return List.copyOf(out);
    }

    private static boolean looksLikeImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 6) {
            return false;
        }
        // JPEG / PNG / GIF / WEBP / 常见不明头仍放行较大载荷
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return true;
        }
        if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50) {
            return true;
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return true;
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return true;
        }
        // 拒绝明显 HTML/JSON 错误页
        String head = new String(bytes, 0, Math.min(64, bytes.length), java.nio.charset.StandardCharsets.US_ASCII)
                .toLowerCase(Locale.ROOT);
        if (head.contains("<html") || head.contains("<!doctype") || head.contains("{")) {
            return false;
        }
        return bytes.length >= 256;
    }

    private static byte[] httpDownload(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://wx.qq.com/")
                .GET()
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String sniffImageMime(byte[] bytes, String fallback) {
        if (bytes != null && bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes != null && bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50) {
            return "image/png";
        }
        if (bytes != null && bytes.length >= 6
                && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes != null && bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "image/webp";
        }
        return firstNonBlank(fallback, "image/jpeg");
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').trim();
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    /** 顶层类型或引用媒体类型。 */
    private static String effectiveMediaType(MsgInfo msg) {
        String type = MsgType.normalize(msg.msgType());
        if (isMediaType(type)) {
            return type;
        }
        String quoteType = string(msg.extra() == null ? null : msg.extra().get(ChannelExtraKeys.QUOTE_MSG_TYPE));
        if (isMediaType(quoteType)) {
            return quoteType;
        }
        return type;
    }

    private static long[] idsFromReplyTo(String replyToMsgId) {
        long svrid = parseLong(replyToMsgId, 0L);
        if (svrid <= 0) {
            return null;
        }
        // downloadImageByMsg(id=clientId, newId=svrid)；缺 clientId 时用 0
        return new long[]{svrid, 0L};
    }

    private MsgInfo rewrite(MsgInfo msg, ResolvedMedia resolved) throws Exception {
        if (resolved.bytes().length > properties.getMediaMaxBytes()) {
            log.warn("Golem media too large bytes={} max={}", resolved.bytes().length, properties.getMediaMaxBytes());
            return msg;
        }
        Map<String, Object> extra = new HashMap<>(msg.extra() == null ? Map.of() : msg.extra());
        String form = properties.getMediaPreferForm() == null
                ? "FILE"
                : properties.getMediaPreferForm().trim().toUpperCase(Locale.ROOT);

        String path;
        MediaRef ref;
        if ("BASE64".equals(form) && resolved.bytes().length <= 2 * 1024 * 1024) {
            String b64 = java.util.Base64.getEncoder().encodeToString(resolved.bytes());
            ref = MediaRef.base64(b64, resolved.mime()).withSize((long) resolved.bytes().length);
            path = ref.applyToExtra(extra);
        } else {
            Path dir = Path.of(properties.getMediaStorePath()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String name = UUID.randomUUID().toString().replace("-", "") + "." + resolved.ext();
            Path file = dir.resolve(name);
            Files.write(file, resolved.bytes());
            ref = MediaRef.file(file.toString()).withMime(resolved.mime()).withSize((long) resolved.bytes().length);
            path = ref.applyToExtra(extra);
        }
        log.info("Golem media resolved type={} form={} bytes={} path={}",
                msg.msgType(), ref.form(), resolved.bytes().length, path);
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

    private static boolean isMediaType(String type) {
        return MsgType.IMAGE.equals(type)
                || MsgType.VIDEO.equals(type)
                || MsgType.AUDIO.equals(type)
                || MsgType.FILE.equals(type)
                || MsgType.EMOJI.equals(type);
    }

    /** msgId = newId:clientId:createTime:peer → [newId, clientId] */
    private static long[] parsePackedIds(String msgId) {
        if (msgId == null || msgId.isBlank()) {
            return null;
        }
        String[] parts = msgId.split(":", 4);
        if (parts.length < 2) {
            return null;
        }
        long newId = parseLong(parts[0], 0L);
        long clientId = parseLong(parts[1], 0L);
        if (newId <= 0 && clientId <= 0) {
            return null;
        }
        return new long[]{newId, clientId};
    }

    private static String guessExt(String type, String mime) {
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("png")) return "png";
            if (m.contains("webp")) return "webp";
            if (m.contains("gif")) return "gif";
            if (m.contains("mp4")) return "mp4";
            if (m.contains("silk") || m.contains("audio")) return "silk";
        }
        return switch (MsgType.normalize(type)) {
            case MsgType.VIDEO -> "mp4";
            case MsgType.AUDIO -> "silk";
            case MsgType.FILE -> "bin";
            default -> "jpg";
        };
    }

    private static String guessMime(String type, String mime) {
        if (mime != null && !mime.isBlank()) {
            return mime;
        }
        return switch (MsgType.normalize(type)) {
            case MsgType.VIDEO -> "video/mp4";
            case MsgType.AUDIO -> "audio/silk";
            case MsgType.FILE -> "application/octet-stream";
            default -> "image/jpeg";
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long parseLong(Object value, long def) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (Exception ignored) {
                return def;
            }
        }
        return def;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record ResolvedMedia(byte[] bytes, String ext, String mime) {
    }
}
