package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.common.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis：SET {@code agbot:golem:session-active}，成员 {@code accountId:u:userId} / {@code accountId:g:groupId}。
 */
public class RedisGolemSessionActivation implements GolemSessionActivation {
    private static final Logger log = LoggerFactory.getLogger(RedisGolemSessionActivation.class);
    private static final String REDIS_KEY = RedisKey.of("golem", "session-active");

    private final StringRedisTemplate redis;

    public RedisGolemSessionActivation(StringRedisTemplate redis) {
        this.redis = redis;
        log.info("Golem session activation using Redis key={}", REDIS_KEY);
    }

    @Override
    public boolean isActive(String accountId, String peerKey) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(REDIS_KEY,
                GolemSessionActivation.redisMember(accountId, peerKey)));
    }

    @Override
    public void activate(String accountId, String peerKey) {
        redis.opsForSet().add(REDIS_KEY, GolemSessionActivation.redisMember(accountId, peerKey));
    }

    @Override
    public void deactivate(String accountId, String peerKey) {
        redis.opsForSet().remove(REDIS_KEY, GolemSessionActivation.redisMember(accountId, peerKey));
    }
}
