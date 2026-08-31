package me.liwncy.agbot.kernel.chatlog.mcp;

import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.chatlog.ChatLogQuery;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import me.liwncy.agbot.kernel.chatlog.ChatLogTime;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

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
    private static final int DEFAULT_WINDOW_LIMIT = 200;
    private static final int MAX_LIMIT = 200;
    private static final int LINE_CLIP = 200;

    private final ChatLogService chatLog;

    public AgbotChatMcpTool(ChatLogService chatLog) {
        this.chatLog = chatLog;
    }

    @McpTool(
            name = "agbot_chat_history",
            description = "查询当前会话通道聊天记录（微信群/私聊原文，含未回复的）。"
                    + "认人、对「他/刚才谁说」、按某天/时间段翻记录时用。"
                    + "scope 必须从本条消息前缀原样复制（group:… 或 user:…），不要猜、不要改 @chatroom。"
                    + "查某天用 date（2026-08-29 / 8-29 / 29号，可带 14:30:05）。总结某天必须带 date，limit 默认 200。"
                    + "按时间点用 from/until，精确到秒。满页用 afterId 继续拿更晚的，再一起总结；不要说只有 50 条。"
                    + "闲聊接话不要调。accountId 默认 default。"
    )
    public String history(
            @McpToolParam(description = "必填。本条前缀里的 scope，如 group:123@chatroom 或 user:wxid_xxx")
            String scope,
            @McpToolParam(description = "机器人账号槽，默认 default")
            String accountId,
            @McpToolParam(description = "条数 1-200。无时间窗默认 20；有 date/from/until 默认 200")
            Integer limit,
            @McpToolParam(description = "某一天。可带时分秒：2026-08-29 或 2026-08-29 14:30:05（从该秒到当天结束）。也可用 8-29、29号")
            String date,
            @McpToolParam(description = "窗口起点，含，精确到秒。2026-08-29 或 2026-08-29 17:00:00。有 date 时忽略")
            String from,
            @McpToolParam(description = "窗口终点，不含这一刻，精确到秒。只写日期则含当天。有 date 时忽略")
            String until,
            @McpToolParam(description = "只看最近 N 小时，1-168。有 date/from/until 时忽略")
            Integer hours,
            @McpToolParam(description = "发言人：wxid_… 精确匹配；否则按 id 或昵称模糊匹配")
            String speaker,
            @McpToolParam(description = "正文关键词，模糊匹配 content")
            String keyword,
            @McpToolParam(description = "消息类型：text/image/emoji/video/audio/app/link 等")
            String msgType,
            @McpToolParam(description = "翻更早：填上一页返回的 beforeId（最近流水用）")
            String beforeId,
            @McpToolParam(description = "翻更晚：总结某天满页后填返回的 afterId，继续拿后面")
            String afterId,
            @McpToolParam(description = "可选 inbound / outbound；不传则全部")
            String direction,
            @McpToolParam(description = "可选 wechat / example；golem 会当成 wechat")
            String platform
    ) {
        String sessionId = normalizeScope(scope);
        if (sessionId.isEmpty()) {
            return "缺少 scope。请从本条消息前缀复制 scope= 后面那一段（group:… 或 user:…）。";
        }
        ChatLogTime.Resolve window = ChatLogTime.resolve(date, from, until, hours);
        if (window.failed()) {
            return window.error();
        }
        boolean windowed = window.window().since() != null || window.window().until() != null;
        int size = sanitizeLimit(limit, windowed);
        SpeakerFilter who = speakerFilter(speaker);
        Long olderThan = parseId(beforeId);
        Long newerThan = parseId(afterId);
        String type = normalizeMsgType(msgType);
        ChatLogQuery query = new ChatLogQuery(
                blankTo(accountId, "default"),
                sessionId,
                blankToNull(platform),
                normalizeDirection(direction),
                window.window().since(),
                window.window().until(),
                who.senderId(),
                who.senderName(),
                blankToNull(keyword),
                type,
                olderThan,
                newerThan,
                size
        );
        List<ChatMessage> rows = chatLog.listRecent(query);
        log.info("MCP agbot_chat_history session={} account={} limit={} window={} speaker={} keyword={} type={} beforeId={} afterId={} hits={}",
                sessionId, query.accountId(), size, window.window().label(), speaker, keyword, type, olderThan, newerThan, rows.size());
        if (rows.isEmpty()) {
            return emptyHint(window.window());
        }
        return formatList(rows, window.window(), size, windowed && olderThan == null);
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
        return formatList(rows, null, Integer.MAX_VALUE, false);
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

    private static int sanitizeLimit(Integer limit, boolean windowed) {
        if (limit == null || limit < 1) {
            return windowed ? DEFAULT_WINDOW_LIMIT : DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String emptyHint(ChatLogTime.Window window) {
        StringBuilder sb = new StringBuilder("没有找到记录。核对 scope 是否与前缀完全一致（含 @chatroom）。");
        if (window != null && (window.since() != null || window.until() != null)) {
            sb.append(" 当前窗口：").append(window.label()).append("。");
            sb.append("库里只存接入后的通道原文，窗口之前的补不回来。");
        }
        return sb.toString();
    }

    private static SpeakerFilter speakerFilter(String speaker) {
        String text = blankToNull(speaker);
        if (text == null) {
            return new SpeakerFilter(null, null);
        }
        if (isStableId(text)) {
            return new SpeakerFilter(text, null);
        }
        return new SpeakerFilter(text, text);
    }

    private static boolean isStableId(String speaker) {
        String text = speaker.toLowerCase(Locale.ROOT);
        return text.startsWith("wxid_") || text.contains("@");
    }

    private static String normalizeMsgType(String msgType) {
        String text = blankToNull(msgType);
        if (text == null) {
            return null;
        }
        String normalized = MsgType.normalize(text);
        return MsgType.ALL.contains(normalized) ? normalized : null;
    }

    private static Long parseId(String raw) {
        String text = blankToNull(raw);
        if (text == null) {
            return null;
        }
        try {
            long id = Long.parseLong(text);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static String formatList(List<ChatMessage> rows, ChatLogTime.Window window, int pageSize, boolean pageForward) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(rows.size()).append(" 条（旧→新）");
        if (window != null) {
            sb.append(" 窗口=").append(window.label());
        }
        sb.append('\n');
        for (ChatMessage row : rows) {
            sb.append(formatLine(row)).append('\n');
        }
        if (rows.size() >= pageSize && pageSize > 0 && pageSize < Integer.MAX_VALUE) {
            if (pageForward) {
                Long newest = rows.get(rows.size() - 1).getId();
                if (newest != null) {
                    sb.append("本页已满，窗口里可能还有更晚。再查时 afterId=").append(newest)
                            .append("，把两页拼一起再总结，不要说已经看完。");
                }
            } else {
                Long oldest = rows.get(0).getId();
                if (oldest != null) {
                    sb.append("本页已满，可能还有更早。再查时 beforeId=").append(oldest);
                }
            }
        }
        return sb.toString().trim();
    }

    private record SpeakerFilter(String senderId, String senderName) {
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
