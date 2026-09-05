package me.liwncy.agbot.kernel.api.message;

import java.util.Set;

/**
 * 通道内容类型上限（出站 {@link ReplyInfo#type()} / 入站 {@link MsgInfo#msgType()}）。
 * <p>契约按能力最全适配器消息面声明；弱适配器用 capabilities 降级，勿删常量。</p>
 */
public final class MsgType {
    public static final String TEXT = "text";
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";
    public static final String FILE = "file";
    public static final String EMOJI = "emoji";
    public static final String LINK = "link";
    /** 出站音乐卡；适配器拼平台结构，无音频可降级为 {@link #LINK}。 */
    public static final String MUSIC = "music";
    public static final String CARD = "card";
    public static final String APP = "app";
    public static final String POSITION = "position";
    public static final String FORWARD = "forward";

    /** 入站语音别名，适配器应归一为 {@link #AUDIO}。 */
    public static final String VOICE = "voice";

    public static final Set<String> ALL = Set.of(
            TEXT, IMAGE, VIDEO, AUDIO, FILE, EMOJI, LINK, MUSIC, CARD, APP, POSITION, FORWARD
    );

    private MsgType() {
    }

    /**
     * 将平台别名归一为契约 type（如 voice→audio）。
     */
    public static String normalize(String type) {
        if (type == null || type.isBlank()) {
            return TEXT;
        }
        String t = type.trim().toLowerCase();
        if (VOICE.equals(t)) {
            return AUDIO;
        }
        return t;
    }
}
