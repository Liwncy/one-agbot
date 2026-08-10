package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MediaForm;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;

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
            case MsgType.EMOJI -> captionOr(msgInfo.msg(), "请看这个表情");
            case MsgType.LINK -> linkContent(msgInfo, extra);
            case MsgType.CARD -> "[名片] " + firstNonBlank(msgInfo.msg(),
                    stringExtra(extra, ChannelExtraKeys.CARD_USERNAME));
            case MsgType.POSITION -> positionContent(msgInfo.msg(), extra);
            case MsgType.APP -> appContent(msgInfo.msg(), extra);
            case MsgType.FORWARD -> firstNonBlank(msgInfo.msg(), "请查看转发内容");
            default -> firstNonBlank(msgInfo.msg(), "[" + type + "]");
        };
        return new AgentUserInput(type, withSpeaker(msgInfo, appendQuote(content, extra)), media);
    }

    /**
     * 私聊/群聊均在正文前标注发言人。
     * 昵称可改，必须以稳定 {@code userId} 为准；昵称仅作可读展示。
     * 例：{@code [wxid_abc/张三] 你好}；无昵称则 {@code [wxid_abc] 你好}。
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
        if (text.isBlank()) {
            return "[" + speaker + "]";
        }
        return "[" + speaker + "] " + text;
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

    private static String linkContent(MsgInfo msgInfo, Map<String, Object> extra) {
        String title = blankToEmpty(msgInfo.msg());
        String url = firstNonBlank(stringExtra(extra, "url"), blankToEmpty(msgInfo.path()));
        StringBuilder sb = new StringBuilder("请查看链接");
        if (!title.isBlank()) {
            sb.append("：").append(title);
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
        String appType = stringExtra(extra, ChannelExtraKeys.APP_TYPE);
        String text = blankToEmpty(body);
        if (!appType.isBlank()) {
            return firstNonBlank(text, "应用消息 type=" + appType);
        }
        return firstNonBlank(text, "应用消息");
    }

    private static String appendQuote(String text, Map<String, Object> extra) {
        String quote = stringExtra(extra, ChannelExtraKeys.QUOTE_CONTENT);
        if (quote.isBlank()) {
            return text;
        }
        String quoteType = stringExtra(extra, ChannelExtraKeys.QUOTE_MSG_TYPE);
        StringBuilder sb = new StringBuilder();
        if (!text.isBlank()) {
            sb.append(text).append('\n');
        }
        sb.append("[引用");
        if (!quoteType.isBlank()) {
            sb.append(':').append(quoteType);
        }
        sb.append("] ").append(trim(quote, 500));
        return sb.toString().trim();
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
