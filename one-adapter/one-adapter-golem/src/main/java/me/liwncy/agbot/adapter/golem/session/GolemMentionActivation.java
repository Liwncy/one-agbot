package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.common.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群聊点名后的连续对话窗口：同一用户短时间内免 @ 可继续聊（对齐 xchatbot activation）。
 */
public class GolemMentionActivation {
    private static final Logger log = LoggerFactory.getLogger(GolemMentionActivation.class);

    private final StringRedisTemplate redis;
    private final Duration window;
    private final Map<String, Long> localExpireAt = new ConcurrentHashMap<>();

    public GolemMentionActivation(StringRedisTemplate redis, Duration window) {
        this.redis = redis;
        this.window = window == null || window.isNegative() ? Duration.ZERO : window;
        if (this.window.isZero()) {
            log.info("Golem mention activation window disabled");
        } else if (redis != null) {
            log.info("Golem mention activation using Redis window={}", this.window);
        } else {
            log.info("Golem mention activation using memory window={}", this.window);
        }
    }

    public boolean isActive(String accountId, String groupId, String userId) {
        if (window.isZero()) {
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

    public void touch(String accountId, String groupId, String userId) {
        if (window.isZero()) {
            return;
        }
        String key = key(accountId, groupId, userId);
        if (redis != null) {
            redis.opsForValue().set(redisKey(key), "1", window);
            return;
        }
        localExpireAt.put(key, System.currentTimeMillis() + window.toMillis());
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
