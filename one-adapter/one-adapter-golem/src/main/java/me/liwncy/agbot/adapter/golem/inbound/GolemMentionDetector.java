package me.liwncy.agbot.adapter.golem.inbound;

import java.text.Normalizer;
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
    /** 微信 @ 后常见的窄空格等 */
    private static final Pattern WECHAT_NOISE = Pattern.compile("[\\u2005\\u2006\\u2009\\u200A\\u200B\\uFEFF\\u00A0]+");

    private GolemMentionDetector() {
    }

    public static boolean isBotMentioned(String content,
                                         String pushContent,
                                         String msgSource,
                                         String botWechatId,
                                         String botWechatName) {
        String botId = trim(botWechatId);
        String botName = trim(botWechatName);
        if (botId.isEmpty() && botName.isEmpty()) {
            return false;
        }

        String rawPush = pushContent == null ? "" : pushContent;
        // 微信系统预览：别人 @ 机器人时常见「xxx在群聊中@了你」，正文未必带昵称明文
        if (rawPush.contains("在群聊中@了你") || rawPush.contains("@了你")) {
            return true;
        }

        String text = normalizeForMatch(join(content, pushContent));
        String source = decodeXml(msgSource == null ? "" : msgSource);

        if (!botId.isEmpty()) {
            if (text.contains(normalizeForMatch(botId))) {
                return true;
            }
            if (atuserListContains(source, botId)) {
                return true;
            }
        }
        if (!botName.isEmpty()) {
            String normalizedName = normalizeForMatch(botName);
            if (!normalizedName.isEmpty()) {
                // 正文/预览任意位置出现昵称
                if (text.contains(normalizedName)) {
                    return true;
                }
                // @ 与昵称之间可能有空格：@ 小聪明儿
                if (Pattern.compile("@\\s*" + Pattern.quote(normalizedName)).matcher(text).find()) {
                    return true;
                }
                if (Pattern.compile("＠\\s*" + Pattern.quote(normalizedName)).matcher(text).find()) {
                    return true;
                }
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
            text = text.replaceFirst("(?s)^[@＠]?\\s*\\Q" + botName + "\\E[\\s,，:：\\u2005\\u2006-]*", "").trim();
            text = text.replace("＠" + botName, " ").replace("@" + botName, " ");
            text = text.replace(botName, " ");
            text = WECHAT_NOISE.matcher(text).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
        }
        if (!botId.isEmpty()) {
            text = text.replaceFirst("(?s)^[@＠]?\\s*\\Q" + botId + "\\E[\\s,，:：\\u2005\\u2006-]*", "").trim();
            text = text.replace("@" + botId, " ").replace(botId, " ");
            text = WECHAT_NOISE.matcher(text).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
        }
        return text;
    }

    private static boolean atuserListContains(String source, String botId) {
        if (source.isEmpty() || botId.isEmpty()) {
            return false;
        }
        String decoded = decodeXml(source);
        Matcher matcher = AT_USER_LIST.matcher(decoded);
        while (matcher.find()) {
            String body = matcher.group(1) == null ? "" : matcher.group(1);
            body = body.replace("<![CDATA[", "").replace("]]>", "");
            for (String part : body.split("[\\n,;，；\\s]+")) {
                String id = part.trim();
                if (id.equalsIgnoreCase(botId)) {
                    return true;
                }
            }
        }
        String lower = decoded.toLowerCase(Locale.ROOT);
        return lower.contains("atuserlist") && lower.contains(botId.toLowerCase(Locale.ROOT));
    }

    private static String normalizeForMatch(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC);
        text = WECHAT_NOISE.matcher(text).replaceAll("");
        return text.trim();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(part);
        }
        return sb.toString();
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
