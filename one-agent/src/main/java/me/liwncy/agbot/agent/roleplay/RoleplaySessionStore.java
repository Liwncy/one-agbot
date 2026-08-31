package me.liwncy.agbot.agent.roleplay;

/**
 * scopeKey → 角色 id。没演角色时不写值。
 */
public interface RoleplaySessionStore {

    String get(String scopeKey);

    void set(String scopeKey, String characterId);

    void clear(String scopeKey);
}
