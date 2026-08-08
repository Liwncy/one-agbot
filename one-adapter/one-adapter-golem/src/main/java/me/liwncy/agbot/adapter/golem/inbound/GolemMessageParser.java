package me.liwncy.agbot.adapter.golem.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import me.liwncy.agbot.adapter.golem.GolemAdapter;
import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.common.json.JsonUtils;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Golem 推送信封归一为 {@link MsgInfo}（参照 xchatbot parse-payload）。
 */
public final class GolemMessageParser {
    private GolemMessageParser() {
    }

    public static List<MsgInfo> parse(String accountId, String rawJson, GolemProperties properties) {
        JsonNode root = JsonUtils.fromJson(rawJson, JsonNode.class);
        JsonNode items = root == null ? null : root.get("new_message");
        if (items == null || !items.isArray() || items.isEmpty()) {
            return List.of();
        }
        List<MsgInfo> result = new ArrayList<>();
        for (JsonNode item : items) {
            MsgInfo msg = parseItem(accountId, item, properties);
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }

    private static MsgInfo parseItem(String accountId, JsonNode item, GolemProperties properties) {
        int type = item.path("type").asInt(0);
        String source = inferSource(item);
        if ("official".equals(source) && !properties.isAllowOfficial()) {
            return null;
        }
        // MVP：仅文本进入 Agent；其它类型先跳过
        if (type != 1) {
            return null;
        }

        String sender = textValue(item, "sender");
        String receiver = textValue(item, "receiver");
        String rawContent = item.path("content").path("value").asText("");
        if (rawContent.isBlank()) {
            rawContent = item.path("push_content").asText("");
        }
        String pushContent = item.path("push_content").asText("");
        String msgSource = firstNonBlank(
                item.path("msg_source").asText(null),
                item.path("source").asText(null),
                ""
        );
        String userName = parseSenderName(pushContent);

        String groupId = "0";
        String userId;
        String content;
        if ("group".equals(source)) {
            groupId = resolveRoomId(sender, receiver);
            GroupText groupText = parseGroupTextSender(rawContent);
            userId = groupText.senderId();
            if (userId == null || userId.isBlank()) {
                userId = sender != null && !sender.endsWith("@chatroom") ? sender : "";
            }
            content = resolveTextContent(groupText.content(), pushContent);
        } else {
            userId = sender == null ? "" : sender;
            content = resolveTextContent(rawContent, pushContent);
        }
        if (userId == null || userId.isBlank()) {
            return null;
        }
        if (content == null || content.isBlank()) {
            return null;
        }

        boolean botMentioned = GolemMentionDetector.isBotMentioned(
                content, msgSource, properties.getBotWechatId(), properties.getBotWechatName());
        if ("group".equals(source) && botMentioned) {
            content = GolemMentionDetector.stripMentionPrefix(
                    content, properties.getBotWechatId(), properties.getBotWechatName());
            if (content.isBlank()) {
                content = "你好";
            }
        }

        String msgId = firstNonBlank(
                asTextOrNull(item, "id"),
                asTextOrNull(item, "msg_id"),
                asTextOrNull(item, "new_id"),
                asTextOrNull(item, "new_msg_id"),
                String.valueOf(item.path("create_time").asLong(System.currentTimeMillis() / 1000))
        );
        long createTimeMs = toEpochMillis(item.path("create_time").asLong(0));

        Map<String, Object> extra = new HashMap<>();
        extra.put("source", source);
        extra.put("receiver", receiver == null ? "" : receiver);
        extra.put("botMentioned", botMentioned);
        if (item.has("new_id") || item.has("new_msg_id")) {
            extra.put("newId", firstNonBlank(asTextOrNull(item, "new_id"), asTextOrNull(item, "new_msg_id")));
        }

        return new MsgInfo(
                GolemAdapter.PLATFORM,
                accountId,
                userId,
                userName,
                groupId,
                null,
                content,
                msgId,
                "Social",
                MsgType.TEXT,
                null,
                null,
                createTimeMs,
                Map.copyOf(extra)
        );
    }

    private static String inferSource(JsonNode item) {
        String sourceHint = firstNonBlank(
                item.path("source").asText(null),
                item.path("msg_source").asText(null),
                ""
        ).toLowerCase();
        String sender = textValue(item, "sender");
        String receiver = textValue(item, "receiver");
        if (sourceHint.contains("official")) {
            return "official";
        }
        if (sourceHint.contains("chatroom")
                || (sender != null && sender.endsWith("@chatroom"))
                || (receiver != null && receiver.endsWith("@chatroom"))) {
            return "group";
        }
        return "private";
    }

    private static String resolveRoomId(String sender, String receiver) {
        if (receiver != null && receiver.endsWith("@chatroom")) {
            return receiver;
        }
        if (sender != null && sender.endsWith("@chatroom")) {
            return sender;
        }
        return receiver == null ? "0" : receiver;
    }

    private static GroupText parseGroupTextSender(String rawContent) {
        String text = rawContent == null ? "" : rawContent;
        int nl = text.indexOf(":\n");
        int crlf = text.indexOf(":\r\n");
        int idx = nl > 0 ? nl : crlf;
        int sepLen = nl > 0 ? 2 : 3;
        if (idx <= 0) {
            return new GroupText(null, text);
        }
        String senderId = text.substring(0, idx).trim();
        String content = text.substring(idx + sepLen);
        if (senderId.isEmpty()) {
            return new GroupText(null, text);
        }
        return new GroupText(senderId, content);
    }

    private static String parseSenderName(String pushContent) {
        if (pushContent == null || pushContent.isBlank()) {
            return null;
        }
        for (String sep : List.of(" : ", ": ", "：", ":")) {
            int i = pushContent.indexOf(sep);
            if (i <= 0) {
                continue;
            }
            String name = pushContent.substring(0, i).trim();
            if (name.isEmpty() || name.contains("@chatroom") || name.regionMatches(true, 0, "wxid_", 0, 5)) {
                continue;
            }
            return name;
        }
        return null;
    }

    private static String resolveTextContent(String content, String pushContent) {
        String text = content == null ? "" : content;
        if (!isUnsupportedClientContent(text)) {
            return text;
        }
        String preview = parsePreviewText(pushContent);
        return preview == null || preview.isBlank() ? text : preview;
    }

    private static String parsePreviewText(String pushContent) {
        if (pushContent == null || pushContent.isBlank()) {
            return null;
        }
        for (String sep : List.of(" : ", ": ", "：", ":")) {
            int i = pushContent.indexOf(sep);
            if (i <= 0) {
                continue;
            }
            return pushContent.substring(i + sep.length()).trim();
        }
        return pushContent.trim();
    }

    private static boolean isUnsupportedClientContent(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return false;
        }
        return text.contains("无法显示此消息");
    }

    private static long toEpochMillis(long ts) {
        if (ts <= 0) {
            return System.currentTimeMillis();
        }
        return ts > 1_000_000_000_000L ? ts : ts * 1000L;
    }

    private static String textValue(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        String value = node.path("value").asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String asTextOrNull(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record GroupText(String senderId, String content) {
    }
}
