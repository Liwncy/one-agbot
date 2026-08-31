package me.liwncy.agbot.kernel.support;

import me.liwncy.agbot.common.redis.RedisKey;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis 会话映射。
 */
public class RedisConversationMapper implements ConversationMapper {
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisConversationMapper(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public String resolveConversationId(String sessionKey) {
        String key = RedisKey.of("conv", sessionKey);
        String existing = redis.opsForValue().get(key);
        if (existing != null && !existing.isBlank()) {
            redis.expire(key, ttl);
            return existing;
        }
        String created = UUID.randomUUID().toString().replace("-", "");
        Boolean ok = redis.opsForValue().setIfAbsent(key, created, ttl);
        if (Boolean.FALSE.equals(ok)) {
            String raced = redis.opsForValue().get(key);
            return raced != null ? raced : created;
        }
        return created;
    }

    @Override
    public void reset(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        redis.delete(RedisKey.of("conv", sessionKey));
    }
}
