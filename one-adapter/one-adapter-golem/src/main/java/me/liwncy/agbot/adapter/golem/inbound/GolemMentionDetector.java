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
                // 仅认 @昵称，避免正文里碰巧出现昵称字样、或「问别人」被当成问机器人
                if (Pattern.compile("[@＠]\\s*" + Pattern.quote(normalizedName)).matcher(text).find()) {
                    return true;
                }
                if (Pattern.compile("[@＠]\\s*" + Pattern.quote(botName)).matcher(rawContent).find()
                        || Pattern.compile("[@＠]\\s*" + Pattern.quote(botName)).matcher(rawPush).find()) {
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

    /** 正文里的 @xxx（微信窄空格后常见） */
    private static final Pattern AT_TOKEN = Pattern.compile("[@＠]\\s*([^\\s@＠]{1,40})");

    /**
     * 是否在跟别人说话（没点名机器人）：
     * <ol>
     *   <li>{@code atuserlist} 里有别人、没有机器人</li>
     *   <li>正文/预览里 @ 了别人昵称，且没有 @ 机器人</li>
     * </ol>
     * 若已明确 @ 机器人或引用机器人消息，返回 false（可同时 @ 多人）。
     */
    public static boolean isTalkingToOthersOnly(List<String> mentionIds,
                                                String content,
                                                String pushContent,
                                                String botWechatId,
                                                String botWechatName) {
        return isTalkingToOthersOnly(mentionIds, content, pushContent,
                botWechatId, botWechatName, null, null);
    }

    public static boolean isTalkingToOthersOnly(List<String> mentionIds,
                                                String content,
                                                String pushContent,
                                                String botWechatId,
                                                String botWechatName,
                                                String quoteFrom,
                                                String quoteFromName) {
        // 已点名机器人（含「@了你」、正文 @昵称、atuserlist、引用机器人）→ 不是「只跟别人说」
        boolean botHit = atuserListHasBot(mentionIds, botWechatId)
                || textAtsBot(content, botWechatId, botWechatName)
                || textAtsBot(pushContent, botWechatId, botWechatName)
                || containsAtYouHint(content, pushContent)
                || isQuoteOfBot(quoteFrom, quoteFromName, botWechatId, botWechatName);
        if (botHit) {
            return false;
        }
        if (isAddressedToOthersOnly(mentionIds, botWechatId)) {
            return true;
        }
        return textAtsOthersOnly(content, botWechatId, botWechatName)
                || textAtsOthersOnly(pushContent, botWechatId, botWechatName);
    }

    /**
     * 是否「点名了别人、却没点名机器人」（仅看 atuserlist）。
     */
    public static boolean isAddressedToOthersOnly(List<String> mentionIds, String botWechatId) {
        if (mentionIds == null || mentionIds.isEmpty()) {
            return false;
        }
        String botId = trim(botWechatId);
        boolean botMentioned = false;
        boolean others = false;
        for (String id : mentionIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!botId.isEmpty() && id.equalsIgnoreCase(botId)) {
                botMentioned = true;
            } else {
                others = true;
            }
        }
        return others && !botMentioned;
    }

    private static boolean atuserListHasBot(List<String> mentionIds, String botWechatId) {
        String botId = trim(botWechatId);
        if (botId.isEmpty() || mentionIds == null) {
            return false;
        }
        for (String id : mentionIds) {
            if (id != null && id.equalsIgnoreCase(botId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAtYouHint(String content, String pushContent) {
        String rawPush = pushContent == null ? "" : pushContent;
        String rawContent = content == null ? "" : content;
        return rawPush.contains("在群聊中@了你") || rawPush.contains("@了你")
                || rawContent.contains("在群聊中@了你") || rawContent.contains("@了你");
    }

    private static boolean textAtsBot(String text, String botWechatId, String botWechatName) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String botId = trim(botWechatId);
        String botName = trim(botWechatName);
        Matcher matcher = AT_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = cleanAtToken(matcher.group(1));
            if (token.isEmpty()) {
                continue;
            }
            if (!botId.isEmpty() && token.equalsIgnoreCase(botId)) {
                return true;
            }
            if (!botName.isEmpty() && (token.equals(botName) || normalizeForMatch(token).equals(normalizeForMatch(botName)))) {
                return true;
            }
        }
        return false;
    }

    /** 正文 @ 了非机器人对象，且没 @ 机器人。 */
    private static boolean textAtsOthersOnly(String text, String botWechatId, String botWechatName) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String botId = trim(botWechatId);
        String botName = trim(botWechatName);
        boolean others = false;
        boolean bot = false;
        Matcher matcher = AT_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = cleanAtToken(matcher.group(1));
            if (token.isEmpty() || "所有人".equals(token) || "all".equalsIgnoreCase(token)) {
                continue;
            }
            boolean isBot = (!botId.isEmpty() && token.equalsIgnoreCase(botId))
                    || (!botName.isEmpty() && (token.equals(botName)
                    || normalizeForMatch(token).equals(normalizeForMatch(botName))));
            if (isBot) {
                bot = true;
            } else {
                others = true;
            }
        }
        return others && !bot;
    }

    private static String cleanAtToken(String raw) {
        if (raw == null) {
            return "";
        }
        String token = WECHAT_NOISE.matcher(raw.trim()).replaceAll("");
        return token.replaceAll("[，,。.!！？?、:：；;]+$", "").trim();
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
