package me.liwncy.agbot.adapter.golem.inbound;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群聊点名检测（对齐 xchatbot：正文含昵称 / @昵称 / wxid，msg_source atuserlist，以及引用机器人消息）。
 */
public final class GolemMentionDetector {
    private static final Pattern AT_USER_LIST = Pattern.compile(
            "<atuserlist(?:\\s[^>]*)?>([\\s\\S]*?)</atuserlist>",
            Pattern.CASE_INSENSITIVE);
    /** 微信点名：@群昵称 后跟 U+2005 等空白 */
    private static final Pattern AT_DISPLAY = Pattern.compile(
            "[@＠]([^@＠\\u2004\\u2005\\u2006\\u2009\\u200A\\u200B\\uFEFF\\s]+)");
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
        String rawContent = content == null ? "" : content;
        // 微信系统预览：别人 @ 机器人时常见「xxx在群聊中@了你」，正文未必带昵称明文
        if (rawPush.contains("在群聊中@了你") || rawPush.contains("@了你")
                || rawContent.contains("在群聊中@了你") || rawContent.contains("@了你")) {
            return true;
        }

        String text = normalizeForMatch(join(content, pushContent));
        String source = decodeXml(msgSource == null ? "" : msgSource);

        if (!botId.isEmpty()) {
            // 正文里写了机器人 wxid，或 atuserlist 明确点到机器人
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
                // 正文/预览任意位置出现昵称（加不加 @ 都算点名）
                if (text.contains(normalizedName)) {
                    return true;
                }
                // @ 与昵称之间可能有空格/特殊空白：@ 小聪明儿
                if (Pattern.compile("[@＠]\\s*" + Pattern.quote(normalizedName)).matcher(text).find()) {
                    return true;
                }
                if (rawContent.contains(botName) || rawPush.contains(botName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 引用/回复的原消息是否来自机器人（refermsg fromusr / displayname）。
     */
    public static boolean isQuoteOfBot(String quoteFrom,
                                       String quoteFromName,
                                       String botWechatId,
                                       String botWechatName) {
        String botId = trim(botWechatId);
        String botName = trim(botWechatName);
        String from = trim(quoteFrom);
        String fromName = trim(quoteFromName);
        if (!botId.isEmpty() && !from.isEmpty() && botId.equalsIgnoreCase(from)) {
            return true;
        }
        if (!botName.isEmpty() && !fromName.isEmpty()
                && normalizeForMatch(botName).equals(normalizeForMatch(fromName))) {
            return true;
        }
        return false;
    }

    /**
     * 从 msg_source 的 {@code <atuserlist>} 提取被 @ 的 wxid 列表（去重保序）。
     */
    public static List<String> extractMentionIds(String msgSource) {
        if (msgSource == null || msgSource.isBlank()) {
            return List.of();
        }
        String decoded = decodeXml(msgSource);
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = AT_USER_LIST.matcher(decoded);
        while (matcher.find()) {
            String body = matcher.group(1) == null ? "" : matcher.group(1);
            body = body.replace("<![CDATA[", "").replace("]]>", "");
            for (String part : body.split("[\\n,;，；\\s]+")) {
                String id = part.trim();
                if (!id.isEmpty() && !"notify@all".equalsIgnoreCase(id)) {
                    ids.add(id);
                }
            }
        }
        return ids.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(ids));
    }

    /**
     * 从正文抽出 @ 后面的展示名（保序）。对应微信点名插入的 {@code @昵称 + U+2005}。
     */
    public static List<String> extractAtDisplayNames(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = AT_DISPLAY.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (name.isEmpty() || name.length() > 30) {
                continue;
            }
            if ("所有人".equals(name) || "all".equalsIgnoreCase(name) || "notify@all".equalsIgnoreCase(name)) {
                continue;
            }
            names.add(name);
        }
        return names.isEmpty() ? List.of() : List.copyOf(names);
    }

    /**
     * 去掉正文中的机器人点名，仅给本地口令识别用（开机 / 模式）。
     * 进 Agent 的原文不要剥，否则名单中间的 {@code @机器人} 会消失。
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
