package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.api.message.MediaForm;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * 从 {@link MediaRef} 读取字节，供 OpenAPI 资源上传（不做 CDN 解析）。
 */
final class AgentMediaLoader {
    private static final Logger log = LoggerFactory.getLogger(AgentMediaLoader.class);
    private static final Set<String> IMAGE_TYPES = Set.of(MsgType.IMAGE, MsgType.EMOJI);
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private AgentMediaLoader() {
    }

    /**
     * 若可上传为 OpenAPI IMAGE 附件则返回字节；否则 null。
     */
    static LoadedImage loadImageAttachment(String msgType, MediaRef media) {
        if (media == null || !media.usableForFetch()) {
            return null;
        }
        String type = MsgType.normalize(msgType);
        // 视频封面若被解析成 image/jpeg，也允许挂 IMAGE；文本引用图同理
        boolean allow = IMAGE_TYPES.contains(type)
                || (MsgType.VIDEO.equals(type) && isImageMime(firstNonBlank(media.mime(), "image/jpeg")))
                || (MsgType.TEXT.equals(type) && isImageMime(firstNonBlank(media.mime(), "image/jpeg")));
        if (!allow) {
            return null;
        }
        try {
            byte[] bytes = readBytes(media);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                log.warn("Image too large for OpenAPI attachment bytes={}", bytes.length);
                return null;
            }
            String sniffed = sniffMime(bytes);
            String mime = firstNonBlank(sniffed, media.mime(), "image/jpeg");
            if (!isImageMime(mime)) {
                log.warn("Skip non-image mime for attachment mime={}", mime);
                return null;
            }
            // SnailAI OpenAPI 只收 JPG/PNG/WEBP；微信表情多为 GIF，取首帧压成 JPG
            if (isGifMime(mime) || isGifMime(sniffed)) {
                byte[] jpeg = gifToJpeg(bytes);
                if (jpeg == null || jpeg.length == 0) {
                    log.warn("Convert gif to jpeg failed type={} bytes={}", type, bytes.length);
                    return null;
                }
                if (jpeg.length > MAX_IMAGE_BYTES) {
                    log.warn("Converted jpeg too large for OpenAPI attachment bytes={}", jpeg.length);
                    return null;
                }
                log.info("Converted gif attachment to jpeg srcBytes={} jpegBytes={}", bytes.length, jpeg.length);
                return new LoadedImage(jpeg, "image/jpeg", "chat-" + type + ".jpg");
            }
            String name = "chat-" + type + extOf(mime);
            return new LoadedImage(bytes, mime, name);
        } catch (Exception e) {
            log.warn("Load media for attachment failed form={}: {}", media.form(), e.getMessage());
            return null;
        }
    }

    private static byte[] readBytes(MediaRef media) throws Exception {
        return switch (media.form()) {
            case BASE64 -> Base64.getDecoder().decode(media.base64().replaceAll("\\s", ""));
            case FILE -> Files.readAllBytes(Path.of(media.path()));
            case URL -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create(media.path()))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://wx.qq.com/")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    yield response.body();
                }
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            case PLATFORM -> null;
        };
    }

    private static boolean isImageMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return false;
        }
        String m = mime.toLowerCase(Locale.ROOT);
        return m.startsWith("image/")
                && (m.contains("jpeg") || m.contains("jpg") || m.contains("png") || m.contains("webp") || m.contains("gif"));
    }

    private static boolean isGifMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).contains("gif");
    }

    /** 动图只取首帧；透明通道铺白底（JPEG 无 alpha）。 */
    private static byte[] gifToJpeg(byte[] gifBytes) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(gifBytes));
            if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
                return null;
            }
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, src.getWidth(), src.getHeight());
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "jpg", out)) {
                return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("gifToJpeg failed: {}", e.getMessage());
            return null;
        }
    }

    private static String sniffMime(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50) {
            return "image/png";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "image/webp";
        }
        return null;
    }

    private static String extOf(String mime) {
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";
        if (m.contains("gif")) return ".gif";
        return ".jpg";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    record LoadedImage(byte[] bytes, String mime, String fileName) {
    }
}
