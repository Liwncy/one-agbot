package me.liwncy.agbot.kernel.chatlog;

import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 把落库行收成给模型看的短行。
 */
public final class ChatLogLines {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private ChatLogLines() {
    }

    public static String contextBlock(List<ChatMessage> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage row : rows) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(contextLine(row));
        }
        return sb.toString();
    }

    static String contextLine(ChatMessage row) {
        String clock = row.getMsgTime() == null ? "--:--" : CLOCK.format(row.getMsgTime());
        String speaker = speaker(row.getSenderId(), row.getSenderName());
        String body = clip(row.getContentText(), 120);
        if (body.isBlank()) {
            body = "[" + (row.getMsgType() == null ? "msg" : row.getMsgType()) + "]";
        }
        return clock + " " + speaker + ": " + body;
    }

    static String speaker(String senderId, String senderName) {
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
}
