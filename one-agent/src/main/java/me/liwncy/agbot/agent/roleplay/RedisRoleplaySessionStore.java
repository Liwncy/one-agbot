package me.liwncy.agbot.agent.roleplay;

import me.liwncy.agbot.common.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis Hash {@code agbot:roleplay:session}，field=scopeKey，无 TTL。
 */
public class RedisRoleplaySessionStore implements RoleplaySessionStore {
    private static final Logger log = LoggerFactory.getLogger("agbot.agent");
    static final String REDIS_KEY = RedisKey.of("roleplay", "session");

    private final StringRedisTemplate redis;

    public RedisRoleplaySessionStore(StringRedisTemplate redis) {
        this.redis = redis;
        log.info("Roleplay session using Redis key={}", REDIS_KEY);
    }

    @Override
    public String get(String scopeKey) {
        if (blank(scopeKey)) {
            return null;
        }
        Object value = redis.opsForHash().get(REDIS_KEY, scopeKey);
        if (value == null) {
            return null;
        }
        String id = String.valueOf(value).trim();
        return id.isEmpty() ? null : id;
    }

    @Override
    public void set(String scopeKey, String characterId) {
        if (blank(scopeKey) || blank(characterId)) {
            return;
        }
        redis.opsForHash().put(REDIS_KEY, scopeKey, characterId);
    }

    @Override
    public void clear(String scopeKey) {
        if (blank(scopeKey)) {
            return;
        }
        redis.opsForHash().delete(REDIS_KEY, scopeKey);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
