package me.liwncy.agbot.adapter.golem.inbound;

import me.liwncy.agbot.kernel.api.message.MsgType;

/**
 * 微信数字 type → 通道 {@link MsgType}（对齐 xchatbot mapWechatType，并覆盖名片/好友申请）。
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
            case 49 -> MsgType.APP; // link / file / quote / forward 等在解析里再细分
            case 42 -> MsgType.CARD;
            default -> null;
        };
    }

    /** 好友申请走独立分支，不经 {@link #toMsgType}。 */
    static boolean isFriendVerify(int wechatType) {
        return wechatType == 37 || wechatType == 65;
    }

    /**
     * 系统提示/撤回/状态同步等噪音：入站直接丢弃，避免进 Agent。
     * <ul>
     *   <li>10000 系统提示</li>
     *   <li>10002 撤回等 sysmsg</li>
     *   <li>51/52 状态同步</li>
     * </ul>
     */
    static boolean isSystemNoise(int wechatType) {
        return wechatType == 10000
                || wechatType == 10002
                || wechatType == 51
                || wechatType == 52;
    }

    static boolean isKnown(int wechatType) {
        return toMsgType(wechatType) != null || isFriendVerify(wechatType);
    }
}
