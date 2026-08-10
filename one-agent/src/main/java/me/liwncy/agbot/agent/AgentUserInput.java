package me.liwncy.agbot.agent;

import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgType;

/**
 * Agent 侧用户输入：按 {@link MsgType} 识别；媒体通过 {@link MediaRef} 多种传输形态承载。
 * <p>CDN/下载等由适配器完成后再写入契约，Agent 只消费可用形态。</p>
 */
public record AgentUserInput(
        String msgType,
        String content,
        MediaRef media
) {
    public AgentUserInput {
        msgType = MsgType.normalize(msgType);
        if (content == null) {
            content = "";
        }
    }

    public boolean hasMedia() {
        return media != null;
    }

    public boolean hasUsableMedia() {
        return media != null && media.usableForFetch();
    }

    /** 兼容旧语义：URL/FILE 的 path，或 PLATFORM id。 */
    public String mediaPath() {
        if (media == null) {
            return null;
        }
        return switch (media.form()) {
            case URL, FILE -> media.path();
            case PLATFORM -> media.platformId() != null ? media.platformId() : media.path();
            case BASE64 -> null;
        };
    }

    public boolean isImage() {
        return MsgType.IMAGE.equals(msgType);
    }

    public boolean isVideo() {
        return MsgType.VIDEO.equals(msgType);
    }

    public boolean isAudio() {
        return MsgType.AUDIO.equals(msgType);
    }
}
