package me.liwncy.agbot.kernel.chatlog.mcp;

import me.liwncy.agbot.kernel.chatlog.ChatLogQuery;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 框架自带 MCP：查 {@code agbot_chat_message}。
 */
public class AgbotChatMcpTool {
    private static final Logger log = LoggerFactory.getLogger(AgbotChatMcpTool.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_HOURS = 168;
    private static final int LINE_CLIP = 200;

    private final ChatLogService chatLog;

    public AgbotChatMcpTool(ChatLogService chatLog) {
        this.chatLog = chatLog;
    }

    @McpTool(
            name = "agbot_chat_history",
            description = "查询当前会话近期通道聊天记录（微信群/私聊原文，含未回复的）。"
                    + "认人、对「他/刚才谁说」时用。scope 必须从本条消息前缀原样复制（group:… 或 user:…），不要猜、不要改 @chatroom。"
                    + "闲聊接话不要调。accountId 默认 default。"
    )
    public String history(
            @McpToolParam(description = "必填。本条前缀里的 scope，如 group:123@chatroom 或 user:wxid_xxx")
            String scope,
            @McpToolParam(description = "机器人账号槽，默认 default")
            String accountId,
            @McpToolParam(description = "条数 1-50，默认 20")
            Integer limit,
            @McpToolParam(description = "只看最近 N 小时，1-168；不传则按条数")
            Integer hours,
            @McpToolParam(description = "可选 inbound / outbound；不传则全部")
            String direction,
            @McpToolParam(description = "可选 wechat / example；golem 会当成 wechat")
            String platform
    ) {
        String sessionId = normalizeScope(scope);
        if (sessionId.isEmpty()) {
            return "缺少 scope。请从本条消息前缀复制 scope= 后面那一段（group:… 或 user:…）。";
        }
        int size = sanitizeLimit(limit);
        LocalDateTime since = toSince(hours);
        ChatLogQuery query = new ChatLogQuery(
                blankTo(accountId, "default"),
                sessionId,
                blankToNull(platform),
                normalizeDirection(direction),
                since,
                size
        );
        List<ChatMessage> rows = chatLog.listRecent(query);
        log.info("MCP agbot_chat_history session={} account={} limit={} hours={} hits={}",
                sessionId, query.accountId(), size, hours, rows.size());
        if (rows.isEmpty()) {
            return "没有找到记录。核对 scope 是否与前缀完全一致（含 @chatroom）。";
        }
        return formatList(rows);
    }

    @McpTool(
            name = "agbot_chat_get",
            description = "按通道 message_id 查一条记录，用来确认引用/刚才那句是谁说的（sender_id + 昵称）。"
                    + "messageId 用引用里的消息 id，不要用昵称猜。"
    )
    public String get(
            @McpToolParam(description = "必填。通道消息 id")
            String messageId,
            @McpToolParam(description = "机器人账号槽，默认 default")
            String accountId
    ) {
        if (messageId == null || messageId.isBlank()) {
            return "缺少 messageId。";
        }
        List<ChatMessage> rows = chatLog.listByMessageId(blankTo(accountId, "default"), messageId.trim());
        log.info("MCP agbot_chat_get messageId={} hits={}", messageId.trim(), rows.size());
        if (rows.isEmpty()) {
            return "没查到这条消息。";
        }
        return formatList(rows);
    }

    static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "";
        }
        String text = scope.trim();
        int eq = text.indexOf('=');
        if (text.toLowerCase(Locale.ROOT).startsWith("scope") && eq > 0) {
            text = text.substring(eq + 1).trim();
        }
        if (text.startsWith("group:") || text.startsWith("user:")) {
            return text;
        }
        if (text.endsWith("@chatroom")) {
            return "group:" + text;
        }
        return "";
    }

    private static int sanitizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static LocalDateTime toSince(Integer hours) {
        if (hours == null || hours < 1) {
            return null;
        }
        int span = Math.min(hours, MAX_HOURS);
        return LocalDateTime.now().minusHours(span);
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        String text = direction.trim().toLowerCase(Locale.ROOT);
        if ("inbound".equals(text) || "outbound".equals(text)) {
            return text;
        }
        return null;
    }

    private static String formatList(List<ChatMessage> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(rows.size()).append(" 条（旧→新）\n");
        for (ChatMessage row : rows) {
            sb.append(formatLine(row)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatLine(ChatMessage row) {
        String time = row.getMsgTime() == null ? "-" : TIME.format(row.getMsgTime());
        String speaker = speaker(row.getSenderId(), row.getSenderName());
        String dir = row.getDirection() == null ? "?" : row.getDirection();
        String type = row.getMsgType() == null ? "text" : row.getMsgType();
        String body = clip(row.getContentText(), LINE_CLIP);
        StringBuilder line = new StringBuilder();
        line.append('[').append(time).append("] ")
                .append(dir).append(' ')
                .append(speaker)
                .append(" type=").append(type);
        if (row.getMessageId() != null && !row.getMessageId().isBlank()) {
            line.append(" id=").append(row.getMessageId());
        }
        line.append(": ").append(body);
        return line.toString();
    }

    private static String speaker(String senderId, String senderName) {
        String id = senderId == null ? "" : senderId.trim();
        String name = senderName == null ? "" : senderName.trim();
        if (name.isBlank() || name.equals(id)) {
            return id.isBlank() ? "unknown" : id;
        }
        if (id.isBlank()) {
            return name;
        }
        return id + "/" + name;
    }

    private static String clip(String text, int max) {
        String body = text == null ? "" : text.replace('\n', ' ').trim();
        if (body.length() <= max) {
            return body;
        }
        return body.substring(0, max) + "...";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
