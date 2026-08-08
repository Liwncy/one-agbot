package me.liwncy.agbot.common.redis;

/**
 * Redis key 前缀。
 */
public final class RedisKey {
    public static final String PREFIX = "agbot:";

    private RedisKey() {
    }

    public static String of(String... parts) {
        return PREFIX + String.join(":", parts);
    }
}
