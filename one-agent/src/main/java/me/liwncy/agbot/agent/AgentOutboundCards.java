package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 文本识别卡片出站协议（单独一行，勿把任意 http 当卡片）：
 * <ul>
 *   <li>{@code link:标题|描述|url|封面}</li>
 *   <li>{@code music:歌名|歌手|页面url|音频url|封面}</li>
 *   <li>{@code app:类型 <xml...>}</li>
 * </ul>
 */
final class AgentOutboundCards {
    private static final Pattern ANY_LINE = Pattern.compile(
            "(?im)^(?:link:.+|music:.+|app:\\d+\\s+<.+)$");
    private static final Pattern APP_LINE = Pattern.compile(
            "(?i)^app:(\\d+)\\s+(<.+)$");

    private AgentOutboundCards() {
    }

    enum Kind {
        LINK, MUSIC, APP
    }

    record Ref(Kind kind, String title, String desc, String url, String dataUrl, String thumb,
               int appType, String xml) {
        boolean hasTarget() {
            if (kind == Kind.APP) {
                return xml != null && !xml.isBlank();
            }
            return looksLikeHttp(url);
        }

        String preview() {
            if (kind == Kind.APP) {
                return "app:" + appType;
            }
            return (kind == Kind.MUSIC ? "music:" : "link:") + firstNonBlank(title, url);
        }

        String toLine() {
            return switch (kind) {
                case LINK -> joinLine("link:", title, desc, url, thumb);
                case MUSIC -> joinLine("music:", title, desc, url, dataUrl, thumb);
                case APP -> "app:" + appType + " " + xml;
            };
        }

        ReplyInfo toReply(MsgInfo inbound) {
            return switch (kind) {
                case LINK -> ReplyInfo.link(
                        firstNonBlank(title, "链接"),
                        blankToEmpty(desc),
                        url,
                        blankToEmpty(thumb),
                        inbound);
                case MUSIC -> ReplyInfo.app(3, musicXml(title, desc, url, dataUrl, thumb), inbound);
                case APP -> ReplyInfo.app(appType, xml, inbound);
            };
        }

        ReplyInfo toLinkFallback(MsgInfo inbound) {
            if (!looksLikeHttp(url)) {
                return null;
            }
            return ReplyInfo.link(
                    firstNonBlank(title, kind == Kind.MUSIC ? "音乐" : "链接"),
                    blankToEmpty(desc),
                    url,
                    blankToEmpty(thumb),
                    inbound);
        }
    }

    record Split(List<Ref> cards, String remainingText) {
        boolean hasCards() {
            return cards != null && !cards.isEmpty();
        }
    }

    static boolean looksLikeCardLine(String text) {
        return parseLine(text) != null;
    }

    static Ref parseLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        Matcher app = APP_LINE.matcher(trimmed);
        if (app.matches()) {
            int type;
            try {
                type = Integer.parseInt(app.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            String xml = app.group(2).trim();
            if (xml.isBlank()) {
                return null;
            }
            return new Ref(Kind.APP, "", "", "", "", "", type, xml);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("link:")) {
            return parsePiped(Kind.LINK, trimmed.substring(5));
        }
        if (lower.startsWith("music:")) {
            return parsePiped(Kind.MUSIC, trimmed.substring(6));
        }
        return null;
    }

    static Split split(String text) {
        if (text == null || text.isBlank()) {
            return new Split(List.of(), text == null ? "" : text);
        }
        List<Ref> refs = new ArrayList<>();
        Matcher m = ANY_LINE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Ref ref = parseLine(m.group());
            if (ref == null || !ref.hasTarget()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            refs.add(ref);
            m.appendReplacement(sb, "\n");
        }
        m.appendTail(sb);
        if (refs.isEmpty()) {
            return new Split(List.of(), text);
        }
        String remaining = sb.toString()
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .trim();
        return new Split(List.copyOf(refs), remaining);
    }

    private static Ref parsePiped(Kind kind, String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 2) {
            return null;
        }
        String title = trimPart(parts, 0);
        String desc = "";
        String url = "";
        String dataUrl = "";
        String thumb = "";
        if (kind == Kind.LINK) {
            if (parts.length == 2) {
                url = trimPart(parts, 1);
            } else if (parts.length == 3) {
                if (looksLikeHttp(trimPart(parts, 1))) {
                    url = trimPart(parts, 1);
                    thumb = trimPart(parts, 2);
                } else {
                    desc = trimPart(parts, 1);
                    url = trimPart(parts, 2);
                }
            } else {
                desc = trimPart(parts, 1);
                url = trimPart(parts, 2);
                thumb = trimPart(parts, 3);
            }
        } else {
            desc = parts.length > 1 ? trimPart(parts, 1) : "";
            url = parts.length > 2 ? trimPart(parts, 2) : "";
            dataUrl = parts.length > 3 ? trimPart(parts, 3) : "";
            thumb = parts.length > 4 ? trimPart(parts, 4) : "";
            if (parts.length == 2 && looksLikeHttp(desc)) {
                url = desc;
                desc = "";
            }
        }
        url = sanitizeUrl(url);
        dataUrl = sanitizeUrl(dataUrl);
        thumb = sanitizeUrl(thumb);
        if (!looksLikeHttp(url)) {
            return null;
        }
        if (!dataUrl.isEmpty() && !looksLikeHttp(dataUrl)) {
            dataUrl = "";
        }
        if (!thumb.isEmpty() && !looksLikeHttp(thumb)) {
            thumb = "";
        }
        return new Ref(kind, title, desc, url, dataUrl, thumb, 0, "");
    }

    static String musicXml(String title, String singer, String url, String dataUrl, String thumb) {
        return "<appmsg appid=\"\" sdkver=\"0\">"
                + "<title>" + escapeXml(firstNonBlank(title, "音乐")) + "</title>"
                + "<des>" + escapeXml(blankToEmpty(singer)) + "</des>"
                + "<action></action>"
                + "<type>3</type>"
                + "<showtype>0</showtype>"
                + "<url>" + escapeXml(blankToEmpty(url)) + "</url>"
                + "<lowurl></lowurl>"
                + "<dataurl>" + escapeXml(blankToEmpty(dataUrl)) + "</dataurl>"
                + "<lowdataurl></lowdataurl>"
                + "<songalbumurl>" + escapeXml(blankToEmpty(thumb)) + "</songalbumurl>"
                + "<songlyric></songlyric>"
                + "</appmsg>";
    }

    private static String joinLine(String prefix, String... fields) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(blankToEmpty(fields[i]));
        }
        return sb.toString();
    }

    private static String trimPart(String[] parts, int i) {
        if (i < 0 || i >= parts.length || parts[i] == null) {
            return "";
        }
        return parts[i].trim();
    }

    private static String sanitizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String url = raw.trim();
        while (!url.isEmpty()) {
            char c = url.charAt(url.length() - 1);
            if (c == ')' || c == ']' || c == '"' || c == '\''
                    || c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == ',' || c == '，' || c == '.') {
                url = url.substring(0, url.length() - 1);
                continue;
            }
            break;
        }
        return url;
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String escapeXml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
