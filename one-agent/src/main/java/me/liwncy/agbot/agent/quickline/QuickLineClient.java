package me.liwncy.agbot.agent.quickline;

import com.fasterxml.jackson.databind.JsonNode;
import me.liwncy.agbot.agent.config.AgbotAgentProperties;
import me.liwncy.agbot.common.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无会话短句补全（OpenAI 兼容 {@code /chat/completions}）。
 * 主 Agent 失败或只需要一句口吻时用；带不了群上下文。
 */
public class QuickLineClient {
    private static final Logger log = LoggerFactory.getLogger("agbot.agent");

    private final AgbotAgentProperties.QuickLine properties;
    private final HttpClient http;

    public QuickLineClient(AgbotAgentProperties properties) {
        this.properties = properties == null || properties.getQuickLine() == null
                ? new AgbotAgentProperties.QuickLine()
                : properties.getQuickLine();
        int connectMs = Math.min(1_000, Math.max(200, this.properties.getTimeoutMs()));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 生成一句。未配置、超时、校验失败时返回 {@code seed}（可为空）。
     */
    public String line(QuickLineSpec spec) {
        String fallback = spec == null || spec.seed() == null ? "" : spec.seed().trim();
        if (spec == null || !properties.ready()) {
            return fallback;
        }
        String instruction = spec.instruction() == null ? "" : spec.instruction().trim();
        if (instruction.isBlank()) {
            return fallback;
        }
        try {
            String raw = complete(spec, instruction);
            String cleaned = sanitize(raw, maxChars(spec));
            if (cleaned.isBlank()) {
                log.info("QuickLine empty task={} fallback={}", spec.task(), preview(fallback));
                return fallback;
            }
            log.info("QuickLine ok task={} speaker={} out={}", spec.task(), speaker(spec), preview(cleaned));
            return cleaned;
        } catch (Exception e) {
            log.warn("QuickLine failed task={} err={}", spec.task(), e.toString());
            return fallback;
        }
    }

    private String complete(QuickLineSpec spec, String instruction) throws Exception {
        String speaker = speaker(spec);
        String system = "你是微信里的" + speaker + "。" + instruction;
        String user = fallbackOr(spec.seed(), "").isBlank()
                ? "按要求说一句。"
                : "原句：" + spec.seed().trim();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel().trim());
        body.put("temperature", 0.9);
        body.put("max_tokens", 64);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));

        HttpRequest request = HttpRequest.newBuilder(URI.create(completionsUrl()))
                .timeout(Duration.ofMillis(Math.max(200, properties.getTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (properties.getApiKey() == null ? "" : properties.getApiKey().trim()))
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + preview(response.body()));
        }
        return extractContent(response.body());
    }

    private String completionsUrl() {
        String base = properties.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private int maxChars(QuickLineSpec spec) {
        if (spec.maxChars() > 0) {
            return spec.maxChars();
        }
        return Math.max(8, properties.getMaxChars());
    }

    private String speaker(QuickLineSpec spec) {
        if (spec.speaker() != null && !spec.speaker().isBlank()) {
            return spec.speaker().trim();
        }
        String configured = properties.getSpeaker();
        return configured == null || configured.isBlank() ? "小聪明儿" : configured.trim();
    }

    private static String extractContent(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return "";
        }
        JsonNode root = JsonUtils.mapper().readTree(json);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isMissingNode() || content.isNull() ? "" : content.asText("");
    }

    static String sanitize(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            text = nl >= 0 ? text.substring(nl + 1) : text.substring(3);
            int end = text.lastIndexOf("```");
            if (end >= 0) {
                text = text.substring(0, end);
            }
            text = text.trim();
        }
        int lineBreak = indexOfLineBreak(text);
        if (lineBreak >= 0) {
            text = text.substring(0, lineBreak).trim();
        }
        if ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("“") && text.endsWith("”"))
                || (text.startsWith("「") && text.endsWith("」"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        String lower = text.toLowerCase();
        if (lower.contains("http://") || lower.contains("https://")
                || lower.contains("[error]")
                || lower.contains("unexpectedstatuscodeexception")) {
            return "";
        }
        if (text.length() > maxChars) {
            return "";
        }
        return text;
    }

    private static int indexOfLineBreak(String text) {
        int n = text.indexOf('\n');
        int r = text.indexOf('\r');
        if (n < 0) {
            return r;
        }
        if (r < 0) {
            return n;
        }
        return Math.min(n, r);
    }

    private static String fallbackOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "...";
    }
}
