package me.liwncy.agbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 发图前探测 URL 是否真能拉到图片，避免把幻觉/截断短链丢给 Golem（会 404）。
 */
final class OutboundImageProbe {
    private static final Logger log = LoggerFactory.getLogger(OutboundImageProbe.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private OutboundImageProbe() {
    }

    static boolean isFetchableImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(imageUrl.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            // 先 HEAD；部分 CDN 不支持再 GET 前几个字节
            if (probe(uri, "HEAD")) {
                return true;
            }
            return probe(uri, "GET");
        } catch (Exception e) {
            log.warn("Image probe failed url={} err={}", preview(imageUrl), e.toString());
            return false;
        }
    }

    private static boolean probe(URI uri, String method) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8));
            if ("HEAD".equals(method)) {
                builder.HEAD();
            } else {
                builder.GET().header("Range", "bytes=0-64");
            }
            HttpResponse<Void> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                log.info("Image probe {} status={} url={}", method, code, preview(uri.toString()));
                return false;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            String ct = contentType.split(";")[0].trim().toLowerCase();
            if (ct.startsWith("image/") || ct.equals("application/octet-stream") || ct.isEmpty()) {
                return true;
            }
            log.info("Image probe {} unexpected content-type={} url={}", method, contentType, preview(uri.toString()));
            return false;
        } catch (Exception e) {
            log.debug("Image probe {} error url={} err={}", method, preview(uri.toString()), e.toString());
            return false;
        }
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }
}
