package me.liwncy.agbot.adapter.golem.inbound;

import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 将入站媒体从 PLATFORM 升级为 FILE/URL/BASE64（下载逻辑仅在适配器内）。
 */
public class GolemMediaResolver {
    private static final Logger log = LoggerFactory.getLogger(GolemMediaResolver.class);

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
        String type = MsgType.normalize(msg.msgType());
        if (!isMediaType(type)) {
            return msg;
        }
        MediaRef current = MediaRef.fromMsg(msg);
        if (current != null && current.usableForFetch()) {
            return msg;
        }
        try {
            ResolvedMedia resolved = download(msg, type, current);
            if (resolved == null || resolved.bytes() == null || resolved.bytes().length == 0) {
                log.warn("Golem media resolve empty type={} msgId={}", type, msg.msgId());
                return msg;
            }
            return rewrite(msg, resolved);
        } catch (Exception e) {
            log.warn("Golem media resolve failed type={} msgId={}: {}", type, msg.msgId(), e.getMessage());
            return msg;
        }
    }

    private ResolvedMedia download(MsgInfo msg, String type, MediaRef current) {
        Map<String, Object> extra = msg.extra() == null ? Map.of() : msg.extra();
        String aesKey = string(extra.get("aeskey"));
        String cdnId = firstNonBlank(
                current == null ? null : current.platformId(),
                current == null ? null : current.path(),
                msg.path(),
                string(extra.get(ChannelExtraKeys.MEDIA_PLATFORM_ID)),
                string(extra.get(ChannelExtraKeys.MEDIA_URL))
        );
        String thumb = string(extra.get(ChannelExtraKeys.THUMB));

        // 1) CDN 下载（图/视频）
        if (!cdnId.isBlank() && !aesKey.isBlank()) {
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
            // 视频可尝试封面
            if (MsgType.VIDEO.equals(type) && !thumb.isBlank()) {
                try {
                    byte[] cover = apiClient.cdnDownloadImage(thumb, aesKey);
                    if (cover.length > 0) {
                        return new ResolvedMedia(cover, "jpg", "image/jpeg");
                    }
                } catch (Exception ignored) {
                    // continue
                }
            }
        }

        // 2) 按消息 id 下载
        long[] ids = parsePackedIds(msg.msgId());
        long size = parseLong(extra.get("length"), parseLong(extra.get(ChannelExtraKeys.MEDIA_SIZE), 0L));
        if (ids != null && size > 0) {
            try {
                byte[] bytes = switch (type) {
                    case MsgType.IMAGE -> apiClient.downloadImageByMsg(ids[1], ids[0], msg.userId(), size);
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
