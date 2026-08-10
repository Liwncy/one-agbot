package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.common.json.JsonUtils;
import me.liwncy.agbot.common.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis：Hash {@code agbot:golem:group-settings}，field=accountId:groupId，value=整份 JSON。
 */
public class RedisGolemGroupRespondPolicy implements GolemGroupRespondPolicy {
    private static final Logger log = LoggerFactory.getLogger(RedisGolemGroupRespondPolicy.class);
    private static final String SETTINGS_KEY = RedisKey.of("golem", "group-settings");

    private final StringRedisTemplate redis;

    public RedisGolemGroupRespondPolicy(StringRedisTemplate redis) {
        this.redis = redis;
        log.info("Golem group settings using Redis key={} default=mention/followUpOff", SETTINGS_KEY);
    }

    @Override
    public GolemGroupSettings get(String accountId, String groupId) {
        Object raw = redis.opsForHash().get(SETTINGS_KEY, GolemGroupRespondPolicy.key(accountId, groupId));
        if (raw == null) {
            return GolemGroupSettings.defaults();
        }
        GolemGroupSettings settings = JsonUtils.fromJson(String.valueOf(raw), GolemGroupSettings.class);
        return settings == null ? GolemGroupSettings.defaults() : settings;
    }

    @Override
    public void save(String accountId, String groupId, GolemGroupSettings settings) {
        GolemGroupSettings value = settings == null ? GolemGroupSettings.defaults() : settings;
        redis.opsForHash().put(
                SETTINGS_KEY,
                GolemGroupRespondPolicy.key(accountId, groupId),
                JsonUtils.toJson(value));
    }

    @Override
    public void ensureDefaults(String accountId, String groupId) {
        String field = GolemGroupRespondPolicy.key(accountId, groupId);
        Boolean exists = redis.opsForHash().hasKey(SETTINGS_KEY, field);
        if (!Boolean.TRUE.equals(exists)) {
            save(accountId, groupId, GolemGroupSettings.defaults());
        }
    }
}
