package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.chatlog.ChatLogView;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * 把通道最近 inbound 垫在本条前面。历史行不带 {@code scope=}，避免工具把旧句当成本条原话。
 */
final class AgentRecentContext {

    static final int LINE_CLIP = 300;

    private AgentRecentContext() {
    }

    static String prepend(String current, List<ChatMessage> rows, int windowMinutes,
                          Map<String, Integer> attachedOrdinalByMessageId) {
        String body = current == null ? "" : current;
        if (rows == null || rows.isEmpty()) {
            return body;
        }
        Map<String, Integer> attached = attachedOrdinalByMessageId == null
                ? Map.of()
                : attachedOrdinalByMessageId;
        StringBuilder sb = new StringBuilder();
        sb.append("[近").append(Math.max(windowMinutes, 0)).append("分钟上下文，不是本条指令");
        if (!attached.isEmpty()) {
            sb.append("；标了附图的已挂在本轮，排在本条附件后面");
        }
        sb.append("]\n");
        int written = 0;
        for (ChatMessage row : rows) {
            String line = formatLine(row, attached.get(row.getMessageId()));
            if (line.isBlank()) {
                continue;
            }
            sb.append(line).append('\n');
            written++;
        }
        if (written == 0) {
            return body;
        }
        sb.append("---\n[本条]\n").append(body);
        return sb.toString();
    }

    static MediaRef mediaRefOf(ChatMessage row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> extra = ChatLogView.extra(row);
        String path = firstNonBlank(
                string(extra.get(ChannelExtraKeys.MEDIA_URL)),
                ChatLogView.looksLikeLocalFile(row.getContentText()) ? row.getContentText() : "");
        return MediaRef.from(path, extra);
    }

    private static String formatLine(ChatMessage row, Integer attachedOrdinal) {
        if (row == null) {
            return "";
        }
        String speaker = speaker(row.getSenderId(), row.getSenderName());
        String type = row.getMsgType() == null ? "" : row.getMsgType().trim();
        String text = ChatLogView.body(row, LINE_CLIP);
        if (!type.isBlank() && !"text".equalsIgnoreCase(type) && !text.startsWith("[")) {
            text = "[" + type + "] " + text;
        }
        if (attachedOrdinal != null && attachedOrdinal > 0) {
            text = text + " （附图" + attachedOrdinal + "）";
        }
        return speaker + ": " + text;
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

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
