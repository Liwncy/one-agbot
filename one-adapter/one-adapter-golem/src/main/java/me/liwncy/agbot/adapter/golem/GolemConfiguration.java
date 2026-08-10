package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.session.FileGolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.GolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.GolemMentionActivation;
import me.liwncy.agbot.adapter.golem.session.RedisGolemGroupGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(GolemProperties.class)
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemConfiguration {
    private static final Logger log = LoggerFactory.getLogger(GolemConfiguration.class);

    /**
     * 优先 Redis；不可用时落盘到本地文件（重启不丢）。
     * <p>用 ObjectProvider 延迟探测：用户 {@code @Configuration} 早于 DataRedis 自动配置解析。</p>
     */
    @Bean
    @ConditionalOnMissingBean(GolemGroupGate.class)
    public GolemGroupGate golemGroupGate(ObjectProvider<StringRedisTemplate> redis,
                                         GolemProperties properties) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && pingRedis(template)) {
            return new RedisGolemGroupGate(template);
        }
        Path storeFile = Path.of(properties.getGroupGateStorePath()).toAbsolutePath().normalize();
        return new FileGolemGroupGate(storeFile);
    }

    @Bean
    @ConditionalOnMissingBean(GolemMentionActivation.class)
    public GolemMentionActivation golemMentionActivation(ObjectProvider<StringRedisTemplate> redis,
                                                         GolemProperties properties) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && pingRedis(template)) {
            return new GolemMentionActivation(template, properties.getGroupActivationWindow());
        }
        return new GolemMentionActivation(null, properties.getGroupActivationWindow());
    }

    private static boolean pingRedis(StringRedisTemplate template) {
        RedisConnectionFactory factory = template.getConnectionFactory();
        if (factory == null) {
            return false;
        }
        try (RedisConnection connection = factory.getConnection()) {
            String pong = connection.ping();
            return pong != null && !pong.isBlank();
        } catch (Exception e) {
            log.warn("Redis unreachable, Golem group gate falls back to file store: {}", e.getMessage());
            return false;
        }
    }
}
