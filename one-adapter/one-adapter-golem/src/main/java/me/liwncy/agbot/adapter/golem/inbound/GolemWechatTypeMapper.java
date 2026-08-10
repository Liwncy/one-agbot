package me.liwncy.agbot.adapter.golem.inbound;

import me.liwncy.agbot.kernel.api.message.MsgType;

/**
 * 微信数字 type → 通道 {@link MsgType}（对齐 xchatbot mapWechatType）。
 */
final class GolemWechatTypeMapper {

    private GolemWechatTypeMapper() {
    }

    static String toMsgType(int wechatType) {
        return switch (wechatType) {
            case 1 -> MsgType.TEXT;
            case 3 -> MsgType.IMAGE;
            case 34 -> MsgType.AUDIO;
            case 43 -> MsgType.VIDEO;
            case 47 -> MsgType.EMOJI;
            case 48 -> MsgType.POSITION;
            case 49 -> MsgType.APP; // link / file / quote 等在解析里再细分
            case 42 -> MsgType.CARD;
            default -> null; // 未知类型：仍可进契约为 text 占位，由 parser 决定
        };
    }

    static boolean isKnown(int wechatType) {
        return toMsgType(wechatType) != null || wechatType == 37;
    }
}
