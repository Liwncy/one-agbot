package me.liwncy.agbot.adapter.golem.session;

/**
 * 群聊启停开关（默认开启）。
 */
public interface GolemGroupGate {

    boolean isEnabled(String accountId, String groupId);

    void enable(String accountId, String groupId);

    void disable(String accountId, String groupId);

    static String key(String accountId, String groupId) {
        return (accountId == null || accountId.isBlank() ? "default" : accountId.trim())
                + ":"
                + (groupId == null ? "" : groupId.trim());
    }
}
