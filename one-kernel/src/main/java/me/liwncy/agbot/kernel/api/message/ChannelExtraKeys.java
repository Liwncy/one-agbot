package me.liwncy.agbot.kernel.api.message;

/**
 * {@link MsgInfo#extra()} / {@link ReplyInfo#extra()} 约定键（非一等字段的扩展）。
 */
public final class ChannelExtraKeys {
    public static final String PARSE_MODE = "parseMode";
    public static final String MENTION_IDS = "mentionIds";
    public static final String QUOTE_CONTENT = "quoteContent";
    public static final String QUOTE_MSG_TYPE = "quoteMsgType";
    /** 被引用消息的发送者 wxid（refermsg chatusr/fromusr） */
    public static final String QUOTE_FROM = "quoteFrom";
    /** 被引用消息的发送者展示名（refermsg displayname） */
    public static final String QUOTE_FROM_NAME = "quoteFromName";
    public static final String THUMB = "thumb";
    public static final String DURATION = "duration";
    public static final String MD5 = "md5";
    public static final String FORMAT = "format";

    /** 媒体传输形态，见 {@link MediaForm} */
    public static final String MEDIA_FORM = "mediaForm";
    /** 与 path 等价的 URL 备份（可选） */
    public static final String MEDIA_URL = "mediaUrl";
    public static final String MEDIA_BASE64 = "mediaBase64";
    public static final String MEDIA_PLATFORM_ID = "mediaPlatformId";
    public static final String MEDIA_MIME = "mediaMime";
    public static final String MEDIA_SIZE = "mediaSize";
    public static final String CARD_USERNAME = "cardUsername";
    public static final String CARD_NICKNAME = "cardNickname";
    public static final String CARD_ALIAS = "cardAlias";
    public static final String APP_TYPE = "appType";
    public static final String FORWARD_TYPE = "forwardType";
    /** 链接/应用描述（appmsg des） */
    public static final String DESC = "desc";
    /** 生命周期事件：friend_verify / system 等 */
    public static final String EVENT = "event";
    public static final String LAT = "lat";
    public static final String LON = "lon";
    public static final String LABEL = "label";
    public static final String POI_NAME = "poiName";
    public static final String SCALE = "scale";
    public static final String FRIEND_ID = "friendId";
    public static final String BOT_ID = "botId";

    private ChannelExtraKeys() {
    }
}
