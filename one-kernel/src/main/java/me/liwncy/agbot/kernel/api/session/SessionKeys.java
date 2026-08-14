package me.liwncy.agbot.kernel.api.session;

import me.liwncy.agbot.kernel.api.message.MsgInfo;

/**
 * 会话键规范：{platform}:{accountId}:{group|user}:{id}
 *
 * <p>群聊的 OpenAPI {@code openId} 与 conversation 都按群，不按发言人；
 * 私聊仍按用户，且 {@code externalUserId} 保持历史格式以免换号。
 */
public final class SessionKeys {
    private SessionKeys() {
    }

    public static String of(MsgInfo msg) {
        String peer = msg.isPrivateChat() ? "user:" + msg.userId() : "group:" + msg.groupId();
        return msg.platform() + ":" + msg.accountId() + ":" + peer;
    }

    /**
     * SnailAI OpenAPI 用户外部 id。群聊整群共用一个用户，私聊一人一个。
     */
    public static String externalUserId(MsgInfo msg) {
        if (msg.isPrivateChat()) {
            return msg.platform() + ":" + msg.accountId() + ":" + msg.userId();
        }
        return msg.platform() + ":" + msg.accountId() + ":group:" + msg.groupId();
    }
}
