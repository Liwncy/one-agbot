package me.liwncy.agbot.kernel.api.session;

import me.liwncy.agbot.kernel.api.message.MsgInfo;

/**
 * 会话键规范：{platform}:{accountId}:{group|user}:{id}
 */
public final class SessionKeys {
    private SessionKeys() {
    }

    public static String of(MsgInfo msg) {
        String peer = msg.isPrivateChat() ? "user:" + msg.userId() : "group:" + msg.groupId();
        return msg.platform() + ":" + msg.accountId() + ":" + peer;
    }

    public static String externalUserId(MsgInfo msg) {
        return msg.platform() + ":" + msg.accountId() + ":" + msg.userId();
    }
}
