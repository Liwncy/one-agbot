package me.liwncy.agbot.kernel.api.message;

/**
 * 内容类型（出站 ReplyInfo.type / 入站 msgType）。
 */
public final class MsgType {
    public static final String TEXT = "text";
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";
    public static final String FILE = "file";

    private MsgType() {
    }
}
