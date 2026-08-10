package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.kernel.api.message.MsgInfo;

/**
 * 会话激活：默认未激活，收到指令后才允许进 Agent（避免自动注册/建会话）。
 */
public interface GolemSessionActivation {

    boolean isActive(String accountId, String peerKey);

    void activate(String accountId, String peerKey);

    void deactivate(String accountId, String peerKey);

    static String peerKey(MsgInfo msg) {
        if (msg == null) {
            return "u:unknown";
        }
        if (msg.isPrivateChat()) {
            return "u:" + (msg.userId() == null ? "" : msg.userId());
        }
        return "g:" + (msg.groupId() == null ? "" : msg.groupId());
    }

    static String redisMember(String accountId, String peerKey) {
        return (accountId == null ? "default" : accountId) + ":" + peerKey;
    }
}
