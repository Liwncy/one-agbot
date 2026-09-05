package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 文本识别卡片出站协议（单独一行，定长槽位，勿把任意 http 当卡片）：
 * <ul>
 *   <li>{@code link:标题|描述|url|封面}</li>
 *   <li>{@code music:歌名|歌手|页面url|音频url|封面}</li>
 *   <li>{@code image:图片url}</li>
 *   <li>{@code video:视频url|封面url|时长秒}</li>
 *   <li>{@code audio:语音url|时长秒|格式}（{@code voice:} 同义）</li>
 *   <li>{@code app:类型 <xml...>}</li>
 * </ul>
 */
final class AgentOutboundCards {
    private static final Pattern ANY_LINE = Pattern.compile(
            "(?im)^(?:link:.+|music:.+|video:.+|image:.+|audio:.+|voice:.+|app:\\d+\\s+<.+)$");
    private static final Pattern APP_LINE = Pattern.compile(
            "(?i)^app:(\\d+)\\s+(<.+)$");

    private AgentOutboundCards() {
    }

    enum Kind {
        LINK, MUSIC, VIDEO, IMAGE, AUDIO, APP
    }

    record Ref(Kind kind, String title, String desc, String url, String dataUrl, String thumb, String duration,
               String format, int appType, String xml) {
        boolean hasTarget() {
            if (kind == Kind.APP) {
                return xml != null && !xml.isBlank();
            }
            if (kind == Kind.MUSIC) {
                return looksLikeHttp(url) || looksLikeHttp(dataUrl);
            }
            return looksLikeHttp(url);
        }

        String preview() {
            if (kind == Kind.APP) {
                return "app:" + appType;
            }
            if (kind == Kind.VIDEO) {
                return "video:" + firstNonBlank(url, title);
            }
            if (kind == Kind.IMAGE) {
                return "image:" + firstNonBlank(url, title);
            }
            if (kind == Kind.AUDIO) {
                return "audio:" + firstNonBlank(url, title);
            }
            return (kind == Kind.MUSIC ? "music:" : "link:") + firstNonBlank(title, url);
        }

        String toLine() {
            return switch (kind) {
                case LINK -> joinLine("link:", title, desc, url, thumb);
                case MUSIC -> joinLine("music:", title, desc, url, dataUrl, thumb);
                case VIDEO -> joinLine("video:", url, thumb, duration);
                case IMAGE -> joinLine("image:", url);
                case AUDIO -> joinLine("audio:", url, duration, format);
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
                case MUSIC -> ReplyInfo.music(
                        firstNonBlank(title, "音乐"),
                        blankToEmpty(desc),
                        blankToEmpty(url),
                        blankToEmpty(dataUrl),
                        blankToEmpty(thumb),
                        inbound);
                case VIDEO -> {
                    Map<String, Object> extra = new HashMap<>();
                    if (!blankToEmpty(thumb).isEmpty()) {
                        extra.put(ChannelExtraKeys.THUMB, blankToEmpty(thumb));
                    }
                    if (!blankToEmpty(duration).isEmpty()) {
                        extra.put(ChannelExtraKeys.DURATION, blankToEmpty(duration));
                    }
                    yield ReplyInfo.merge(
                            ReplyInfo.of(MsgType.VIDEO, null, url, null, null, null, extra),
                            inbound);
                }
                case IMAGE -> ReplyInfo.image(url, inbound);
                case AUDIO -> ReplyInfo.audio(url, blankToEmpty(duration), blankToEmpty(format), inbound);
                case APP -> ReplyInfo.app(appType, xml, inbound);
            };
        }

        ReplyInfo toLinkFallback(MsgInfo inbound) {
            String fallbackUrl = looksLikeHttp(url) ? url : dataUrl;
            if (!looksLikeHttp(fallbackUrl)) {
                return null;
            }
            return ReplyInfo.link(
                    firstNonBlank(title, kind == Kind.MUSIC ? "音乐"
                            : kind == Kind.VIDEO ? "视频"
                            : kind == Kind.IMAGE ? "图片"
                            : kind == Kind.AUDIO ? "语音" : "链接"),
                    kind == Kind.VIDEO || kind == Kind.IMAGE || kind == Kind.AUDIO
                            ? "点开看看" : blankToEmpty(desc),
                    fallbackUrl,
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
            return new Ref(Kind.APP, "", "", "", "", "", "", "", type, xml);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("link:")) {
            return parsePiped(Kind.LINK, trimmed.substring(5));
        }
        if (lower.startsWith("music:")) {
            return parsePiped(Kind.MUSIC, trimmed.substring(6));
        }
        if (lower.startsWith("video:")) {
            return parseVideo(trimmed.substring(6));
        }
        if (lower.startsWith("image:")) {
            return parseImage(trimmed.substring(6));
        }
        if (lower.startsWith("audio:") || lower.startsWith("voice:")) {
            return parseAudio(trimmed.substring(6));
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

    /**
     * 定长槽位。末尾空槽可省略；中间空槽必须留 {@code |}。
     * {@code link:} 仅当旧的两段 {@code 标题|url} 时把第 2 槽当作 url，避免跳档误伤历史行。
     */
    private static Ref parsePiped(Kind kind, String payload) {
        String[] parts = payload.split("\\|", -1);
        if (kind == Kind.LINK) {
            String title = trimPart(parts, 0);
            String desc = trimPart(parts, 1);
            String url = sanitizeUrl(trimPart(parts, 2));
            String thumb = sanitizeUrl(trimPart(parts, 3));
            if (parts.length == 2 && looksLikeHttp(desc) && !looksLikeHttp(title)) {
                url = sanitizeUrl(desc);
                desc = "";
            }
            if (!looksLikeHttp(url)) {
                return null;
            }
            if (!thumb.isEmpty() && !looksLikeHttp(thumb)) {
                thumb = "";
            }
            return new Ref(kind, title, desc, url, "", thumb, "", "", 0, "");
        }
        String title = trimPart(parts, 0);
        String desc = trimPart(parts, 1);
        String url = sanitizeUrl(trimPart(parts, 2));
        String dataUrl = sanitizeUrl(trimPart(parts, 3));
        String thumb = sanitizeUrl(trimPart(parts, 4));
        if (!looksLikeHttp(url) && looksLikeHttp(desc) && parts.length == 2) {
            url = sanitizeUrl(desc);
            desc = "";
        }
        if (!dataUrl.isEmpty() && !looksLikeHttp(dataUrl)) {
            dataUrl = "";
        }
        if (!thumb.isEmpty() && !looksLikeHttp(thumb)) {
            thumb = "";
        }
        if (!looksLikeHttp(url) && !looksLikeHttp(dataUrl)) {
            return null;
        }
        return new Ref(kind, title, desc, url, dataUrl, thumb, "", "", 0, "");
    }

    private static Ref parseVideo(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 1) {
            return null;
        }
        String url = sanitizeUrl(trimPart(parts, 0));
        String thumb = sanitizeUrl(parts.length > 1 ? trimPart(parts, 1) : "");
        String duration = blankToEmpty(parts.length > 2 ? trimPart(parts, 2) : "");
        if (!looksLikeHttp(url)) {
            return null;
        }
        if (!thumb.isEmpty() && !looksLikeHttp(thumb)) {
            thumb = "";
        }
        if (!duration.isEmpty() && !duration.matches("\\d+")) {
            duration = "";
        }
        return new Ref(Kind.VIDEO, "", "", url, "", thumb, duration, "", 0, "");
    }

    private static Ref parseImage(String payload) {
        String[] parts = payload.split("\\|", -1);
        String url = sanitizeUrl(trimPart(parts, 0));
        if (!looksLikeHttp(url)) {
            return null;
        }
        return new Ref(Kind.IMAGE, "", "", url, "", "", "", "", 0, "");
    }

    private static Ref parseAudio(String payload) {
        String[] parts = payload.split("\\|", -1);
        String url = sanitizeUrl(trimPart(parts, 0));
        String duration = blankToEmpty(trimPart(parts, 1));
        String format = blankToEmpty(trimPart(parts, 2));
        if (!looksLikeHttp(url)) {
            return null;
        }
        if (!duration.isEmpty() && !duration.matches("\\d+")) {
            duration = "";
        }
        return new Ref(Kind.AUDIO, "", "", url, "", "", duration, format, 0, "");
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
