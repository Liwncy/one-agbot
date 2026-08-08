package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.common.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 持久化群开关（重启不丢）。
 * <p>Key: {@code agbot:golem:group-disabled}，SET 成员为 {@code accountId:groupId}。</p>
 */
public class RedisGolemGroupGate implements GolemGroupGate {
    private static final Logger log = LoggerFactory.getLogger(RedisGolemGroupGate.class);
    private static final String REDIS_KEY = RedisKey.of("golem", "group-disabled");

    private final StringRedisTemplate redis;

    public RedisGolemGroupGate(StringRedisTemplate redis) {
        this.redis = redis;
        try {
            Long size = redis.opsForSet().size(REDIS_KEY);
            log.info("Golem group gate using Redis key={} disabledCount={}",
                    REDIS_KEY, size == null ? 0 : size);
        } catch (Exception e) {
            log.info("Golem group gate using Redis key={} (count unavailable yet: {})",
                    REDIS_KEY, e.getMessage());
        }
    }

    @Override
    public boolean isEnabled(String accountId, String groupId) {
        Boolean member = redis.opsForSet().isMember(REDIS_KEY, GolemGroupGate.key(accountId, groupId));
        return !Boolean.TRUE.equals(member);
    }

    @Override
    public void enable(String accountId, String groupId) {
        redis.opsForSet().remove(REDIS_KEY, GolemGroupGate.key(accountId, groupId));
    }

    @Override
    public void disable(String accountId, String groupId) {
        redis.opsForSet().add(REDIS_KEY, GolemGroupGate.key(accountId, groupId));
    }
}
