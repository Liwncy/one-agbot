package me.liwncy.agbot.agent;

import java.util.regex.Pattern;

/**
 * 清理仅供 Agent 内部使用、不得发送到聊天通道的文本行。
 */
final class AgentOutboundText {
    private static final Pattern ADVISOR_LINE = Pattern.compile(
            "^\\h*\\[Advisor\\h+(?:consultation\\h+#\\d+|review)]\\h*(?:\\R|$)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private AgentOutboundText() {
    }

    static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return ADVISOR_LINE.matcher(text).replaceAll("");
    }
}
