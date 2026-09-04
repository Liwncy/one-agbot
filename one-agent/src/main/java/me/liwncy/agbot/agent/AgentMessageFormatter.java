package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MediaForm;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link MsgInfo} → {@link AgentUserInput}。
 * <p>识别通道类型；媒体形态由 {@link MediaRef} 表达（url/file/base64/platform）。</p>
 */
final class AgentMessageFormatter {

    private AgentMessageFormatter() {
    }

    static AgentUserInput from(MsgInfo msgInfo) {
        if (msgInfo == null) {
            return new AgentUserInput(MsgType.TEXT, "", null);
        }
        String type = MsgType.normalize(msgInfo.msgType());
        MediaRef media = MediaRef.fromMsg(msgInfo);
        Map<String, Object> extra = msgInfo.extra() == null ? Map.of() : msgInfo.extra();
        String content = switch (type) {
            case MsgType.TEXT -> blankToEmpty(msgInfo.msg());
            case MsgType.IMAGE -> captionOr(msgInfo.msg(), "请看这张图片");
            case MsgType.VIDEO -> captionOr(msgInfo.msg(), "请看这段视频");
            case MsgType.AUDIO -> captionOr(msgInfo.msg(), "请听这段语音");
            case MsgType.FILE -> captionOr(msgInfo.msg(), "请查看这个文件");
            case MsgType.EMOJI -> emojiContent(msgInfo, extra);
            case MsgType.LINK -> linkContent(msgInfo, extra);
            case MsgType.CARD -> "[名片] " + firstNonBlank(msgInfo.msg(),
                    stringExtra(extra, ChannelExtraKeys.CARD_USERNAME));
            case MsgType.POSITION -> positionContent(msgInfo.msg(), extra);
            case MsgType.APP -> appContent(msgInfo.msg(), extra);
            case MsgType.FORWARD -> firstNonBlank(msgInfo.msg(), "请查看转发内容");
            default -> firstNonBlank(msgInfo.msg(), "[" + type + "]");
        };
        String text = withSpeaker(msgInfo, appendQuote(content, extra, msgInfo));
        text = appendMentions(text, extra);
        // 引用图/表情/视频封面：顶层仍是 TEXT，附件按引用类型上传
        String agentType = resolveAgentMediaType(type, media, extra);
        return new AgentUserInput(agentType, text, media);
    }

    /**
     * 文本引用携带可用媒体时，把 Agent 侧类型抬到 image/emoji/video，便于挂 OpenAPI 附件。
     */
    private static String resolveAgentMediaType(String msgType, MediaRef media, Map<String, Object> extra) {
        if (media == null) {
            return msgType;
        }
        if (MsgType.IMAGE.equals(msgType) || MsgType.EMOJI.equals(msgType) || MsgType.VIDEO.equals(msgType)) {
            return msgType;
        }
        String quoteType = stringExtra(extra, ChannelExtraKeys.QUOTE_MSG_TYPE);
        if (MsgType.IMAGE.equals(quoteType) || MsgType.EMOJI.equals(quoteType) || MsgType.VIDEO.equals(quoteType)) {
            return quoteType;
        }
        return msgType;
    }

    /**
     * 私聊/群聊均在正文前标注发言人。
     * 昵称可改，必须以稳定 {@code userId} 为准；昵称仅作可读展示。
     * 适配器命中主人时带单词 {@code owner}；自定义 {@code role} 有值才带。
     * 例：{@code [wxid_abc/张三 owner scope=group:123] 你好}；私聊 {@code [wxid_abc scope=user:wxid_abc]}。
     */
    static String withSpeaker(MsgInfo msgInfo, String body) {
        String text = blankToEmpty(body);
        if (msgInfo == null) {
            return text;
        }
        String userId = firstNonBlank(msgInfo.userId(), "unknown");
        String nick = blankToEmpty(msgInfo.userName());
        String speaker = nick.isBlank() || nick.equals(userId)
                ? userId
                : userId + "/" + nick;
        Map<String, Object> extra = msgInfo.extra() == null ? Map.of() : msgInfo.extra();
        StringBuilder prefix = new StringBuilder(speaker);
        if (isOwner(extra)) {
            prefix.append(" owner");
        }
        String role = stringExtra(extra, ChannelExtraKeys.ROLE);
        if (!role.isBlank()) {
            prefix.append(" role=").append(role);
        }
        String scope = msgInfo.isPrivateChat()
                ? "user:" + userId
                : "group:" + firstNonBlank(msgInfo.groupId(), "0");
        prefix.append(" scope=").append(scope);
        if (text.isBlank()) {
            return "[" + prefix + "]";
        }
        return "[" + prefix + "] " + text;
    }

    /**
     * 群 @ 的人：wxid 与群里显示的名字。给编聊天记录卡认人用，不把这行念给用户。
     */
    static String appendMentions(String body, Map<String, Object> extra) {
        String text = blankToEmpty(body);
        List<Map<String, Object>> mentions = mentionRows(extra == null ? null : extra.get(ChannelExtraKeys.MENTIONS));
        if (mentions.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        int fallbackSeq = 1;
        for (Map<String, Object> row : mentions) {
            String id = stringExtra(row, "id");
            String name = stringExtra(row, "name");
            if (id.isBlank() && name.isBlank()) {
                continue;
            }
            String seq = stringExtra(row, "seq");
            if (seq.isBlank()) {
                seq = String.valueOf(fallbackSeq);
            }
            fallbackSeq++;
            String who = name.isBlank() || name.equals(id)
                    ? firstNonBlank(id, name)
                    : id + "/" + name;
            sb.append("\n[被@ ").append(seq).append(' ').append(who).append(']');
        }
        return sb.toString();
    }

    private static List<Map<String, Object>> mentionRows(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean isOwner(Map<String, Object> extra) {
        if (extra == null) {
            return false;
        }
        Object value = extra.get(ChannelExtraKeys.OWNER);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "owner".equalsIgnoreCase(text);
    }

    /** 文本视图：无可用拉取形态时附带 form 提示。 */
    static String toUserMessage(MsgInfo msgInfo) {
        AgentUserInput input = from(msgInfo);
        if (!input.hasMedia()) {
            return input.content();
        }
        MediaRef media = input.media();
        StringBuilder sb = new StringBuilder(input.content());
        sb.append("\n[").append(input.msgType()).append(" form=").append(media.form()).append(']');
        if (media.form() == MediaForm.URL || media.form() == MediaForm.FILE) {
            if (media.path() != null) {
                sb.append(" path=").append(media.path());
            }
        } else if (media.form() == MediaForm.PLATFORM) {
            String id = media.platformId() != null ? media.platformId() : media.path();
            if (id != null) {
                sb.append(" platformId=").append(id);
            }
        } else if (media.form() == MediaForm.BASE64) {
            sb.append(" base64Len=")
                    .append(media.base64() == null ? 0 : media.base64().length());
            if (media.mime() != null) {
                sb.append(" mime=").append(media.mime());
            }
        }
        return sb.toString();
    }

    private static String captionOr(String msg, String fallback) {
        String body = blankToEmpty(msg);
        if (body.isBlank() || body.startsWith("[")) {
            return fallback;
        }
        return body;
    }

    /** 暴露 md5/url，便于 Agent 调 emoji_save；本地文件只提示附图，不把盘符路径塞给模型。 */
    private static String emojiContent(MsgInfo msgInfo, Map<String, Object> extra) {
        String caption = captionOr(msgInfo.msg(), "请看这个表情");
        String md5 = stringExtra(extra, ChannelExtraKeys.MD5);
        String url = firstNonBlank(
                looksLikeHttp(msgInfo == null ? null : msgInfo.path()) ? msgInfo.path() : "",
                looksLikeHttp(stringExtra(extra, ChannelExtraKeys.MEDIA_URL))
                        ? stringExtra(extra, ChannelExtraKeys.MEDIA_URL) : "");
        StringBuilder sb = new StringBuilder(caption);
        if (!md5.isBlank()) {
            sb.append(" md5=").append(md5);
        }
        if (!url.isBlank()) {
            sb.append(" url=").append(url);
        }
        MediaRef media = msgInfo == null ? null : MediaRef.fromMsg(msgInfo);
        if (media != null && media.usableForFetch() && media.form() == MediaForm.FILE) {
            sb.append(" （附图已带上）");
        }
        return sb.toString().trim();
    }

    private static String linkContent(MsgInfo msgInfo, Map<String, Object> extra) {
        String title = blankToEmpty(msgInfo.msg());
        String desc = stringExtra(extra, ChannelExtraKeys.DESC);
        String url = firstNonBlank(stringExtra(extra, "url"), blankToEmpty(msgInfo.path()));
        StringBuilder sb = new StringBuilder("请查看链接");
        if (!title.isBlank()) {
            sb.append("：").append(title);
        }
        if (!desc.isBlank() && !title.contains(desc)) {
            sb.append("（").append(desc).append('）');
        }
        if (!url.isBlank()) {
            sb.append(' ').append(url);
        }
        return sb.toString().trim();
    }

    private static String positionContent(String body, Map<String, Object> extra) {
        String label = firstNonBlank(stringExtra(extra, ChannelExtraKeys.LABEL),
                stringExtra(extra, ChannelExtraKeys.POI_NAME), blankToEmpty(body));
        String lat = stringExtra(extra, ChannelExtraKeys.LAT);
        String lon = stringExtra(extra, ChannelExtraKeys.LON);
        StringBuilder sb = new StringBuilder("位置");
        if (!label.isBlank()) {
            sb.append(' ').append(label);
        }
        if (!lat.isBlank() || !lon.isBlank()) {
            sb.append(" (").append(lat).append(',').append(lon).append(')');
        }
        return sb.toString().trim();
    }

    private static String appContent(String body, Map<String, Object> extra) {
        String text = blankToEmpty(body);
        if (!text.isBlank()) {
            return text;
        }
        String appType = stringExtra(extra, ChannelExtraKeys.APP_TYPE);
        return switch (appType) {
            case "2000" -> "[转账]";
            case "2001" -> "[红包]";
            case "3" -> "[音乐]";
            case "33", "36" -> "[小程序]";
            default -> appType.isBlank() ? "应用消息" : "应用消息 type=" + appType;
        };
    }

    private static String appendQuote(String text, Map<String, Object> extra, MsgInfo msgInfo) {
        String quote = stringExtra(extra, ChannelExtraKeys.QUOTE_CONTENT);
        if (quote.isBlank()) {
            return text;
        }
        // quoteMsgType 已是通道类型（image/text/...）；正文侧已做可读摘要，避免再塞微信 type 数字或 XML
        String quoteType = stringExtra(extra, ChannelExtraKeys.QUOTE_MSG_TYPE);
        String quoteFrom = firstNonBlank(
                stringExtra(extra, ChannelExtraKeys.QUOTE_FROM_NAME),
                stringExtra(extra, ChannelExtraKeys.QUOTE_FROM));
        StringBuilder sb = new StringBuilder();
        if (!text.isBlank()) {
            sb.append(text).append('\n');
        }
        sb.append("[引用");
        if (!quoteType.isBlank() && !"text".equalsIgnoreCase(quoteType)) {
            sb.append(':').append(quoteType);
        }
        if (!quoteFrom.isBlank()) {
            sb.append(' ').append(quoteFrom);
        }
        sb.append("] ").append(trim(quote, 500));
        if (MsgType.EMOJI.equalsIgnoreCase(quoteType) || MsgType.IMAGE.equalsIgnoreCase(quoteType)) {
            String md5 = stringExtra(extra, ChannelExtraKeys.MD5);
            String url = firstNonBlank(
                    stringExtra(extra, ChannelExtraKeys.MEDIA_URL),
                    looksLikeHttp(msgInfo == null ? null : msgInfo.path()) ? msgInfo.path() : "");
            if (!md5.isBlank()) {
                sb.append(" md5=").append(md5);
            }
            if (!url.isBlank()) {
                sb.append(" url=").append(url);
            }
            MediaRef quoteMedia = msgInfo == null ? null : MediaRef.fromMsg(msgInfo);
            if (quoteMedia != null && quoteMedia.usableForFetch()
                    && quoteMedia.form() == MediaForm.FILE) {
                sb.append(" （附图已带上）");
            }
        }
        return sb.toString().trim();
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String trim(String raw, int max) {
        String text = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String stringExtra(Map<String, Object> extra, String key) {
        Object v = extra.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
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
