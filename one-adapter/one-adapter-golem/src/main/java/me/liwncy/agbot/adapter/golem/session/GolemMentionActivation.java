package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.common.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群聊点名后的跟聊窗口运行时状态（TTL）。
 * <p>窗口时长按群配置在 {@link GolemGroupRespondPolicy}，此处只负责「当前是否在窗内」。</p>
 */
public class GolemMentionActivation {
    private static final Logger log = LoggerFactory.getLogger(GolemMentionActivation.class);

    private final StringRedisTemplate redis;
    private final Map<String, Long> localExpireAt = new ConcurrentHashMap<>();

    public GolemMentionActivation(StringRedisTemplate redis) {
        this.redis = redis;
        if (redis != null) {
            log.info("Golem mention follow-up state using Redis");
        } else {
            log.info("Golem mention follow-up state using memory");
        }
    }

    public boolean isActive(String accountId, String groupId, String userId, Duration window) {
        Duration w = normalize(window);
        if (w.isZero()) {
            return false;
        }
        String key = key(accountId, groupId, userId);
        if (redis != null) {
            Boolean has = redis.hasKey(redisKey(key));
            return Boolean.TRUE.equals(has);
        }
        Long expireAt = localExpireAt.get(key);
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            localExpireAt.remove(key, expireAt);
            return false;
        }
        return true;
    }

    public void touch(String accountId, String groupId, String userId, Duration window) {
        Duration w = normalize(window);
        if (w.isZero()) {
            return;
        }
        String key = key(accountId, groupId, userId);
        if (redis != null) {
            redis.opsForValue().set(redisKey(key), "1", w);
            return;
        }
        localExpireAt.put(key, System.currentTimeMillis() + w.toMillis());
    }

    private static Duration normalize(Duration window) {
        if (window == null || window.isNegative() || window.isZero()) {
            return Duration.ZERO;
        }
        return window;
    }

    private static String key(String accountId, String groupId, String userId) {
        return (accountId == null || accountId.isBlank() ? "default" : accountId.trim())
                + ":"
                + (groupId == null ? "" : groupId.trim())
                + ":"
                + (userId == null ? "" : userId.trim());
    }

    private static String redisKey(String key) {
        return RedisKey.of("golem", "mention-active", key);
    }
}
