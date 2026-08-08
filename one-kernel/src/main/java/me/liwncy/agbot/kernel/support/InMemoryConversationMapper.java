package me.liwncy.agbot.kernel.support;

import me.liwncy.agbot.kernel.api.session.ConversationMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话映射（无 Redis 时默认）。
 */
public class InMemoryConversationMapper implements ConversationMapper {
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String resolveConversationId(String sessionKey) {
        return store.computeIfAbsent(sessionKey, k -> UUID.randomUUID().toString().replace("-", ""));
    }
}
