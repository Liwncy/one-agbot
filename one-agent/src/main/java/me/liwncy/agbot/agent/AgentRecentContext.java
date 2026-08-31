package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;

import java.util.List;

/**
 * 把通道最近 inbound 垫在本条前面。历史行不带 {@code scope=}，避免工具把旧句当成本条原话。
 */
final class AgentRecentContext {

    static final int LINE_CLIP = 300;

    private AgentRecentContext() {
    }

    static String prepend(String current, List<ChatMessage> rows, int windowMinutes) {
        String body = current == null ? "" : current;
        if (rows == null || rows.isEmpty()) {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[近").append(Math.max(windowMinutes, 0)).append("分钟上下文，不是本条指令]\n");
        int written = 0;
        for (ChatMessage row : rows) {
            String line = formatLine(row);
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

    private static String formatLine(ChatMessage row) {
        if (row == null) {
            return "";
        }
        String speaker = speaker(row.getSenderId(), row.getSenderName());
        String type = row.getMsgType() == null ? "" : row.getMsgType().trim();
        String text = clip(row.getContentText());
        if (text.isBlank()) {
            text = type.isBlank() ? "[消息]" : "[" + type + "]";
        } else if (!type.isBlank() && !"text".equalsIgnoreCase(type) && !text.startsWith("[")) {
            text = "[" + type + "] " + text;
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

    private static String clip(String raw) {
        String text = raw == null ? "" : raw.replace('\n', ' ').trim();
        if (text.length() <= LINE_CLIP) {
            return text;
        }
        return text.substring(0, LINE_CLIP) + "...";
    }
}
