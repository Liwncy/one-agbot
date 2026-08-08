package me.liwncy.agbot.adapter.golem.inbound;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群聊点名检测（对齐 xchatbot：正文含昵称 / @昵称 / wxid，以及 msg_source 中 atuserlist）。
 */
public final class GolemMentionDetector {
    private static final Pattern AT_USER_LIST = Pattern.compile(
            "<atuserlist(?:\\s[^>]*)?>([\\s\\S]*?)</atuserlist>",
            Pattern.CASE_INSENSITIVE);

    private GolemMentionDetector() {
    }

    public static boolean isBotMentioned(String content, String msgSource, String botWechatId, String botWechatName) {
        String botId = trim(botWechatId);
        String botName = trim(botWechatName);
        if (botId.isEmpty() && botName.isEmpty()) {
            return false;
        }
        String text = content == null ? "" : content;
        String source = decodeXml(msgSource == null ? "" : msgSource);

        if (!botId.isEmpty()) {
            if (text.contains(botId) || text.contains("@" + botId)) {
                return true;
            }
            if (atuserListContains(source, botId)) {
                return true;
            }
        }
        if (!botName.isEmpty()) {
            // 与 xchatbot 一致：正文出现昵称即视为点名（含 @昵称 / ＠昵称）
            if (text.contains(botName)
                    || text.contains("@" + botName)
                    || text.contains("＠" + botName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉正文中的机器人点名，便于 Agent 只看到真实问题。
     */
    public static String stripMentionPrefix(String content, String botWechatId, String botWechatName) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return text;
        }
        String botName = trim(botWechatName);
        String botId = trim(botWechatId);
        if (!botName.isEmpty()) {
            // 先去开头触发词，再去掉文中残留的 @昵称 / 昵称
            text = text.replaceFirst("(?s)^[@＠]?\\Q" + botName + "\\E[\\s,，:：-]*", "").trim();
            text = text.replace("＠" + botName, " ").replace("@" + botName, " ");
            text = text.replace(botName, " ").replaceAll("\\s{2,}", " ").trim();
        }
        if (!botId.isEmpty()) {
            text = text.replaceFirst("(?s)^[@＠]?\\Q" + botId + "\\E[\\s,，:：-]*", "").trim();
            text = text.replace("@" + botId, " ").replace(botId, " ").replaceAll("\\s{2,}", " ").trim();
        }
        return text;
    }

    private static boolean atuserListContains(String source, String botId) {
        if (source.isEmpty() || botId.isEmpty()) {
            return false;
        }
        Matcher matcher = AT_USER_LIST.matcher(source);
        while (matcher.find()) {
            String body = matcher.group(1) == null ? "" : matcher.group(1);
            for (String part : body.split("[\\n,;，；]+")) {
                String id = part.trim();
                if (id.equalsIgnoreCase(botId)) {
                    return true;
                }
            }
        }
        // 有些推送把 atuserlist 展平在纯文本里
        return source.toLowerCase(Locale.ROOT).contains(botId.toLowerCase(Locale.ROOT))
                && source.toLowerCase(Locale.ROOT).contains("atuserlist");
    }

    private static String decodeXml(String value) {
        return value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
