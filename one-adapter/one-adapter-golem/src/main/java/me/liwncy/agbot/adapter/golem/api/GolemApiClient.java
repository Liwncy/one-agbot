package me.liwncy.agbot.adapter.golem.api;

import com.fasterxml.jackson.databind.JsonNode;
import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.common.core.exception.ServiceException;
import me.liwncy.agbot.common.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Golem OpenAPI 客户端（MVP：文本发送）。
 */
public class GolemApiClient {
    private static final Logger log = LoggerFactory.getLogger(GolemApiClient.class);

    private final RestClient restClient;

    public GolemApiClient(GolemProperties properties) {
        String base = properties.getApiBaseUrl() == null ? "" : properties.getApiBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.restClient = RestClient.builder().baseUrl(base).build();
    }

    /**
     * POST /api/message/text
     *
     * @return 网关返回的消息 id（优先 new_id / id）
     */
    public String sendText(String receiver, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", receiver);
        body.put("content", content == null ? "" : content);
        JsonNode root = postJson("/api/message/text", body);
        assertOk(root, "sendText");
        return extractMsgId(root.path("data"));
    }

    private JsonNode postJson(String path, Object body) {
        try {
            String json = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonUtils.toJson(body))
                    .retrieve()
                    .body(String.class);
            return JsonUtils.mapper().readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            log.error("Golem API call failed path={}", path, e);
            throw new ServiceException("Golem API failed: " + e.getMessage());
        }
    }

    private static void assertOk(JsonNode root, String action) {
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw new ServiceException("Golem " + action + ": " + root.path("message").asText("failed"));
        }
    }

    private static String extractMsgId(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return "";
        }
        JsonNode list = data.path("list");
        if (list.isArray() && !list.isEmpty()) {
            JsonNode first = list.get(0);
            String id = firstNonBlank(
                    text(first, "new_id"),
                    text(first, "id"),
                    text(first, "client_id")
            );
            if (!id.isBlank()) {
                return id;
            }
        }
        return firstNonBlank(text(data, "new_id"), text(data, "id"), "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
