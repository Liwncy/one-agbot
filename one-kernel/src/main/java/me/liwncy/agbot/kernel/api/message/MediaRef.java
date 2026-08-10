package me.liwncy.agbot.kernel.api.message;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 媒体引用：通道契约支持的多种传输形态。
 * <p>{@link MsgInfo#path()} / {@link ReplyInfo#path()} 在 {@link MediaForm#URL}/{@link MediaForm#FILE}
 * 时作为主定位符；{@link MediaForm#BASE64}/{@link MediaForm#PLATFORM} 走 extra。</p>
 */
public record MediaRef(
        MediaForm form,
        String path,
        String base64,
        String platformId,
        String mime,
        Long size
) {
    public MediaRef {
        Objects.requireNonNull(form, "form");
        if (path != null && path.isBlank()) {
            path = null;
        }
        if (base64 != null && base64.isBlank()) {
            base64 = null;
        }
        if (platformId != null && platformId.isBlank()) {
            platformId = null;
        }
        if (mime != null && mime.isBlank()) {
            mime = null;
        }
    }

    public static MediaRef url(String url) {
        return new MediaRef(MediaForm.URL, url, null, null, null, null);
    }

    public static MediaRef file(String filePath) {
        return new MediaRef(MediaForm.FILE, filePath, null, null, null, null);
    }

    public static MediaRef base64(String base64, String mime) {
        return new MediaRef(MediaForm.BASE64, null, base64, null, mime, null);
    }

    public static MediaRef platform(String platformId) {
        return new MediaRef(MediaForm.PLATFORM, null, null, platformId, null, null);
    }

    public MediaRef withMime(String mime) {
        return new MediaRef(form, path, base64, platformId, mime, size);
    }

    public MediaRef withSize(Long size) {
        return new MediaRef(form, path, base64, platformId, mime, size);
    }

    public boolean usableForFetch() {
        return switch (form) {
            case URL, FILE -> path != null && !path.isBlank();
            case BASE64 -> base64 != null && !base64.isBlank();
            case PLATFORM -> false;
        };
    }

    /**
     * 从入站消息解析媒体引用；无媒体时返回 null。
     */
    public static MediaRef fromMsg(MsgInfo msg) {
        if (msg == null) {
            return null;
        }
        return from(msg.path(), msg.extra());
    }

    /**
     * 从出站消息解析媒体引用。
     */
    public static MediaRef fromReply(ReplyInfo reply) {
        if (reply == null) {
            return null;
        }
        return from(reply.path(), reply.extra());
    }

    public static MediaRef from(String path, Map<String, Object> extra) {
        Map<String, Object> map = extra == null ? Map.of() : extra;
        MediaForm form = MediaForm.parse(string(map.get(ChannelExtraKeys.MEDIA_FORM)));
        String base64 = string(map.get(ChannelExtraKeys.MEDIA_BASE64));
        String platformId = firstNonBlank(
                string(map.get(ChannelExtraKeys.MEDIA_PLATFORM_ID)),
                string(map.get(ChannelExtraKeys.MD5))
        );
        String mime = string(map.get(ChannelExtraKeys.MEDIA_MIME));
        Long size = longVal(map.get(ChannelExtraKeys.MEDIA_SIZE));
        String locator = firstNonBlank(path, string(map.get(ChannelExtraKeys.MEDIA_URL)));

        if (form == null) {
            form = inferForm(locator, base64, platformId);
        }
        if (form == null) {
            return null;
        }
        return switch (form) {
            case URL -> new MediaRef(MediaForm.URL, locator, null, null, mime, size);
            case FILE -> new MediaRef(MediaForm.FILE, locator, null, null, mime, size);
            case BASE64 -> new MediaRef(MediaForm.BASE64, null, base64, null, mime, size);
            case PLATFORM -> new MediaRef(MediaForm.PLATFORM, locator, null,
                    firstNonBlank(platformId, locator), mime, size);
        };
    }

    /**
     * 写入 extra，并返回应放在 {@code path} 字段的值（URL/FILE 时）。
     */
    public String applyToExtra(Map<String, Object> extra) {
        Objects.requireNonNull(extra, "extra");
        extra.put(ChannelExtraKeys.MEDIA_FORM, form.name());
        if (mime != null) {
            extra.put(ChannelExtraKeys.MEDIA_MIME, mime);
        }
        if (size != null) {
            extra.put(ChannelExtraKeys.MEDIA_SIZE, size);
        }
        return switch (form) {
            case URL -> {
                if (path != null) {
                    extra.put(ChannelExtraKeys.MEDIA_URL, path);
                }
                yield path;
            }
            case FILE -> path;
            case BASE64 -> {
                if (base64 != null) {
                    extra.put(ChannelExtraKeys.MEDIA_BASE64, base64);
                }
                yield null;
            }
            case PLATFORM -> {
                if (platformId != null) {
                    extra.put(ChannelExtraKeys.MEDIA_PLATFORM_ID, platformId);
                }
                yield path != null ? path : platformId;
            }
        };
    }

    public Map<String, Object> toExtra() {
        Map<String, Object> extra = new HashMap<>();
        applyToExtra(extra);
        return extra;
    }

    private static MediaForm inferForm(String locator, String base64, String platformId) {
        if (base64 != null && !base64.isBlank()) {
            return MediaForm.BASE64;
        }
        if (locator != null && !locator.isBlank()) {
            String lower = locator.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return MediaForm.URL;
            }
            if (lower.startsWith("file:") || lower.contains("\\") || lower.startsWith("/")) {
                return MediaForm.FILE;
            }
            // 微信 CDN / aes 等不透明标识
            return MediaForm.PLATFORM;
        }
        if (platformId != null && !platformId.isBlank()) {
            return MediaForm.PLATFORM;
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Long longVal(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
