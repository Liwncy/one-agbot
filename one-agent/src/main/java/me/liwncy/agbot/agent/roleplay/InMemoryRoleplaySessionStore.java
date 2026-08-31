package me.liwncy.agbot.agent.roleplay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 不可用时的内存兜底（重启丢失）。
 */
public class InMemoryRoleplaySessionStore implements RoleplaySessionStore {
    private static final Logger log = LoggerFactory.getLogger("agbot.agent");
    private final Map<String, String> store = new ConcurrentHashMap<>();

    public InMemoryRoleplaySessionStore() {
        log.warn("Roleplay session using in-memory store (Redis unavailable)");
    }

    @Override
    public String get(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return null;
        }
        return store.get(scopeKey);
    }

    @Override
    public void set(String scopeKey, String characterId) {
        if (scopeKey == null || scopeKey.isBlank() || characterId == null || characterId.isBlank()) {
            return;
        }
        store.put(scopeKey, characterId);
    }

    @Override
    public void clear(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return;
        }
        store.remove(scopeKey);
    }
}
