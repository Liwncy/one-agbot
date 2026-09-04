package me.liwncy.agbot.adapter.golem.api;

import com.fasterxml.jackson.databind.JsonNode;
import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.common.core.exception.ServiceException;
import me.liwncy.agbot.common.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Golem OpenAPI 客户端：文本/媒体/链接/表情/撤回等（按通道契约映射）。
 */
public class GolemApiClient {
    private static final Logger log = LoggerFactory.getLogger(GolemApiClient.class);
    /** Golem 发视频时 thumb / thumb_url 必填其一；无封面时用占位 JPEG。 */
    private static final byte[] DEFAULT_VIDEO_THUMB_JPEG = buildDefaultVideoThumbJpeg();

    private final RestClient restClient;

    public GolemApiClient(GolemProperties properties) {
        String base = properties.getApiBaseUrl() == null ? "" : properties.getApiBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.restClient = RestClient.builder().baseUrl(base).build();
    }

    public String sendText(String receiver, String content) {
        return sendText(receiver, content, null);
    }

    public String sendText(String receiver, String content, String remind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", receiver);
        body.put("content", content == null ? "" : content);
        body.put("type", 1);
        if (remind != null && !remind.isBlank()) {
            body.put("remind", remind);
        }
        JsonNode root = postJson("/api/message/text", body);
        assertOk(root, "sendText");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendImage(String receiver, String imageUrl) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("receiver", receiver);
        form.add("image_url", imageUrl == null ? "" : imageUrl);
        JsonNode root = postForm("/api/message/image", form);
        assertOk(root, "sendImage");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendVideo(String receiver, String videoUrl, String thumbUrl, String duration) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("receiver", receiver);
        form.add("video_url", videoUrl == null ? "" : videoUrl);
        form.add("duration", duration == null || duration.isBlank() ? "10" : duration);
        String thumb = thumbUrl == null ? "" : thumbUrl.trim();
        if (!thumb.isBlank()) {
            form.add("thumb_url", thumb);
        } else {
            // 协议侧要求 thumb 文件与 thumb_url 不能同时为空
            form.add("thumb", new ByteArrayResource(DEFAULT_VIDEO_THUMB_JPEG) {
                @Override
                public String getFilename() {
                    return "thumb.jpg";
                }
            });
        }
        JsonNode root = postForm("/api/message/video", form);
        assertOk(root, "sendVideo");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendVoice(String receiver, String voiceUrl, String duration, String format) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("receiver", receiver);
        form.add("voice_url", voiceUrl == null ? "" : voiceUrl);
        form.add("duration", duration == null || duration.isBlank() ? "1000" : duration);
        form.add("format", format == null || format.isBlank() ? "2" : format);
        JsonNode root = postForm("/api/message/voice", form);
        assertOk(root, "sendVoice");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendEmoji(String receiver, String md5, String emojiUrl) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("receiver", receiver);
        if (md5 != null && !md5.isBlank()) {
            form.add("md5", md5);
        }
        if (emojiUrl != null && !emojiUrl.isBlank()) {
            form.add("emoji_url", emojiUrl);
        }
        JsonNode root = postForm("/api/message/emoji", form);
        assertOk(root, "sendEmoji");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendLink(String receiver, String title, String desc, String url, String thumbUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", receiver);
        body.put("title", title == null || title.isBlank() ? "链接" : title);
        body.put("desc", desc == null ? "" : desc);
        body.put("url", url == null ? "" : url);
        body.put("thumb_url", thumbUrl == null ? "" : thumbUrl);
        JsonNode root = postJson("/api/message/link", body);
        assertOk(root, "sendLink");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendCard(String receiver, String username, String nickname, String alias) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", receiver);
        body.put("card_username", username == null ? "" : username);
        body.put("card_nickname", nickname == null ? "" : nickname);
        body.put("card_alias", alias == null ? "" : alias);
        JsonNode root = postJson("/api/message/card", body);
        assertOk(root, "sendCard");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendApp(String receiver, int appType, String xml) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", receiver);
        body.put("type", appType);
        body.put("xml", xml == null ? "" : xml);
        JsonNode root = postJson("/api/message/app", body);
        assertOk(root, "sendApp");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendPosition(String receiver, String label, double lat, double lon,
                               String poiName, int scale) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", receiver);
        body.put("label", label == null ? "" : label);
        body.put("lat", lat);
        body.put("lon", lon);
        body.put("poi_name", poiName == null ? "" : poiName);
        body.put("scale", scale);
        JsonNode root = postJson("/api/message/position", body);
        assertOk(root, "sendPosition");
        return packMsgId(root.path("data"), receiver);
    }

    public String sendForward(String receiver, String type, String xml) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiver", receiver);
        body.put("type", type == null ? "image" : type);
        body.put("xml", xml == null ? "" : xml);
        JsonNode root = postJson("/api/message/forward", body);
        assertOk(root, "sendForward");
        return packMsgId(root.path("data"), receiver);
    }

    /**
     * POST /api/contacts/search。业务码（用户不存在等）由调用方判断，不在这里抛。
     */
    public JsonNode searchContacts(String keyword, int fromScene, int searchScene) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", keyword == null ? "" : keyword);
        body.put("from_scene", fromScene);
        body.put("search_scene", searchScene);
        return postJson("/api/contacts/search", body);
    }

    /**
     * POST /api/contacts/detail。body 为 wxid / 群 id 数组。业务码由调用方判断。
     */
    public JsonNode getContactDetail(List<String> usernames) {
        List<String> ids = new ArrayList<>();
        if (usernames != null) {
            for (String username : usernames) {
                if (username != null && !username.isBlank()) {
                    ids.add(username.trim());
                }
            }
        }
        return postJson("/api/contacts/detail", ids);
    }

    /**
     * GET /api/chatroom/members/{chatroom}。群成员含头像时作 contact/detail 的兜底。
     */
    public JsonNode getChatroomMembers(String chatroom) {
        String room = chatroom == null ? "" : chatroom.trim();
        return getJson("/api/chatroom/members/{chatroom}", room);
    }

    /**
     * GET /api/cdn/download/image?id=&amp;key= → 原始二进制。
     */
    public byte[] cdnDownloadImage(String id, String key) {
        return getBinary("/api/cdn/download/image", Map.of("id", nullToEmpty(id), "key", nullToEmpty(key)));
    }

    /**
     * GET /api/cdn/download/video?id=&amp;key= → 原始二进制。
     */
    public byte[] cdnDownloadVideo(String id, String key) {
        return getBinary("/api/cdn/download/video", Map.of("id", nullToEmpty(id), "key", nullToEmpty(key)));
    }

    /**
     * POST /api/message/download/image，从响应 chunk 取字节。
     */
    public byte[] downloadImageByMsg(long id, long newId, String sender, long size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("new_id", newId);
        body.put("sender", sender == null ? "" : sender);
        body.put("size", size);
        JsonNode root = postJson("/api/message/download/image", body);
        assertOk(root, "downloadImage");
        return extractChunkBytes(root.path("data"));
    }

    /**
     * POST /api/message/download/video。
     */
    public byte[] downloadVideoByMsg(long id, long newId, long size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("new_id", newId);
        body.put("size", size);
        JsonNode root = postJson("/api/message/download/video", body);
        assertOk(root, "downloadVideo");
        return extractChunkBytes(root.path("data"));
    }

    /**
     * POST /api/message/download/voice。
     */
    public byte[] downloadVoiceByMsg(long id, long newId, long bufferId, long length, String groupId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("new_id", newId);
        body.put("buffer_id", bufferId);
        body.put("length", length);
        body.put("group_id", groupId == null ? "" : groupId);
        JsonNode root = postJson("/api/message/download/voice", body);
        assertOk(root, "downloadVoice");
        JsonNode data = root.path("data");
        byte[] fromData = extractBufferBytes(data.path("data"));
        if (fromData.length > 0) {
            return fromData;
        }
        return extractChunkBytes(data);
    }

    /**
     * POST /api/message/revoke；msgId 格式 {@code newId:clientId:createTime:receiver}。
     */
    public void revoke(String packedMsgId) {
        if (packedMsgId == null || packedMsgId.isBlank()) {
            return;
        }
        String[] parts = packedMsgId.split(":", 4);
        if (parts.length < 4) {
            throw new ServiceException("Golem revoke needs msgId as newId:clientId:createTime:receiver");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("new_id", parseLong(parts[0]));
        body.put("client_id", parseLong(parts[1]));
        body.put("create_time", parseLong(parts[2]));
        body.put("receiver", parts[3]);
        JsonNode root = postJson("/api/message/revoke", body);
        assertOk(root, "revoke");
    }

    /**
     * 对齐 xchatbot {@code parseJsonResponse}：HTTP 4xx 仍解析 JSON 业务码，
     * 由调用方判断 code（搜号「用户不存在」要换号，不能在这里抛）。
     */
    private JsonNode postJson(String path, Object body) {
        try {
            String json = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonUtils.toJson(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> { })
                    .body(String.class);
            return readJson(json);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Golem API call failed path={}", path, e);
            throw new ServiceException("Golem API failed: " + e.getMessage());
        }
    }

    private JsonNode getJson(String path, Object... uriVars) {
        try {
            String json = restClient.get()
                    .uri(path, uriVars)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> { })
                    .body(String.class);
            return readJson(json);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Golem API get failed path={}", path, e);
            throw new ServiceException("Golem API failed: " + e.getMessage());
        }
    }

    private JsonNode postForm(String path, MultiValueMap<String, Object> form) {
        try {
            String json = restClient.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> { })
                    .body(String.class);
            return readJson(json);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Golem API form call failed path={}", path, e);
            throw new ServiceException("Golem API failed: " + e.getMessage());
        }
    }

    private static JsonNode readJson(String json) {
        try {
            return JsonUtils.mapper().readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            throw new ServiceException("Golem API returned non-JSON: " + e.getMessage());
        }
    }

    private byte[] getBinary(String path, Map<String, String> query) {
        try {
            byte[] body = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path(path);
                        query.forEach(b::queryParam);
                        return b.build();
                    })
                    .retrieve()
                    .body(byte[].class);
            return body == null ? new byte[0] : body;
        } catch (Exception e) {
            log.error("Golem binary download failed path={} query={}", path, query, e);
            throw new ServiceException("Golem download failed: " + e.getMessage());
        }
    }

    private static byte[] extractChunkBytes(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return new byte[0];
        }
        byte[] chunk = extractBufferBytes(data.path("chunk"));
        if (chunk.length > 0) {
            return chunk;
        }
        return extractBufferBytes(data);
    }

    private static byte[] extractBufferBytes(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new byte[0];
        }
        String raw = firstNonBlank(
                text(node, "data"),
                text(node, "buffer"),
                text(node, "value"),
                node.isTextual() ? node.asText(null) : null
        );
        if (raw == null || raw.isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(raw.replaceAll("\\s", ""));
        } catch (Exception e) {
            try {
                return Base64.getUrlDecoder().decode(raw.replaceAll("\\s", ""));
            } catch (Exception ignored) {
                return new byte[0];
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void assertOk(JsonNode root, String action) {
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw new ServiceException("Golem " + action + ": " + root.path("message").asText("failed"));
        }
    }

    /**
     * 与无界 wxGolem 一致：{@code newId:clientId:createTime:receiver}，便于撤回。
     */
    static String packMsgId(JsonNode data, String receiver) {
        String newId = "";
        String clientId = "0";
        String createTime = "0";
        if (data != null && !data.isMissingNode() && !data.isNull()) {
            JsonNode list = data.path("list");
            JsonNode first = list.isArray() && !list.isEmpty() ? list.get(0) : data;
            newId = firstNonBlank(text(first, "new_id"), text(first, "id"), "");
            clientId = firstNonBlank(text(first, "client_id"), text(first, "id"), "0");
            createTime = firstNonBlank(text(first, "create_time"), "0");
        }
        if (newId.isBlank()) {
            return "";
        }
        return newId + ":" + clientId + ":" + createTime + ":" + (receiver == null ? "" : receiver);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
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

    private static byte[] buildDefaultVideoThumbJpeg() {
        try {
            BufferedImage image = new BufferedImage(180, 320, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(0x2B2B2B));
            g.fillRect(0, 0, 180, 320);
            g.setColor(new Color(0x888888));
            g.fillOval(60, 120, 60, 60);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpg", out)) {
                throw new IllegalStateException("ImageIO cannot write jpg");
            }
            return out.toByteArray();
        } catch (Exception e) {
            // 极小合法 JPEG（1x1），兜底避免类加载失败
            return Base64.getDecoder().decode(
                    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCwAA8A/9k=");
        }
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (Exception e) {
            return 0L;
        }
    }
}
