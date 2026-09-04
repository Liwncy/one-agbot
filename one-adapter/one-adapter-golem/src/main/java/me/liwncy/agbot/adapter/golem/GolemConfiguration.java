package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.adapter.golem.api.GolemChatroomRoster;
import me.liwncy.agbot.adapter.golem.inbound.GolemMediaResolver;
import me.liwncy.agbot.adapter.golem.inbound.GolemMentionEnricher;
import me.liwncy.agbot.adapter.golem.inbound.GolemQuoteMediaEnricher;
import me.liwncy.agbot.adapter.golem.mcp.GolemFakeForwardMcpTool;
import me.liwncy.agbot.adapter.golem.mcp.GolemRandomFriendMcpTool;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import me.liwncy.agbot.adapter.golem.session.FileGolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.FileGolemGroupRespondPolicy;
import me.liwncy.agbot.adapter.golem.session.FileGolemSessionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.GolemGroupRespondPolicy;
import me.liwncy.agbot.adapter.golem.session.GolemMentionActivation;
import me.liwncy.agbot.adapter.golem.session.GolemSessionActivation;
import me.liwncy.agbot.adapter.golem.session.RedisGolemGroupGate;
import me.liwncy.agbot.adapter.golem.session.RedisGolemGroupRespondPolicy;
import me.liwncy.agbot.adapter.golem.session.RedisGolemSessionActivation;
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
    public GolemMentionActivation golemMentionActivation(ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && pingRedis(template)) {
            return new GolemMentionActivation(template);
        }
        return new GolemMentionActivation(null);
    }

    @Bean
    @ConditionalOnMissingBean(GolemMediaResolver.class)
    public GolemMediaResolver golemMediaResolver(GolemApiClient apiClient, GolemProperties properties) {
        return new GolemMediaResolver(apiClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean(GolemChatroomRoster.class)
    public GolemChatroomRoster golemChatroomRoster(GolemApiClient apiClient) {
        return new GolemChatroomRoster(apiClient);
    }

    @Bean
    @ConditionalOnMissingBean(GolemMentionEnricher.class)
    public GolemMentionEnricher golemMentionEnricher(GolemChatroomRoster roster) {
        return new GolemMentionEnricher(roster);
    }

    @Bean
    @ConditionalOnMissingBean(GolemQuoteMediaEnricher.class)
    public GolemQuoteMediaEnricher golemQuoteMediaEnricher(ObjectProvider<ChatLogService> chatLog) {
        return new GolemQuoteMediaEnricher(chatLog.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(GolemSessionActivation.class)
    public GolemSessionActivation golemSessionActivation(ObjectProvider<StringRedisTemplate> redis,
                                                         GolemProperties properties) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && pingRedis(template)) {
            return new RedisGolemSessionActivation(template);
        }
        Path storeFile = Path.of(properties.getSessionActiveStorePath()).toAbsolutePath().normalize();
        return new FileGolemSessionActivation(storeFile);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
    public GolemRandomFriendMcpTool golemRandomFriendMcpTool(GolemApiClient apiClient, GolemProperties properties) {
        return new GolemRandomFriendMcpTool(apiClient, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
    public GolemFakeForwardMcpTool golemFakeForwardMcpTool(GolemChatroomRoster roster) {
        return new GolemFakeForwardMcpTool(roster);
    }

    @Bean
    @ConditionalOnMissingBean(GolemGroupRespondPolicy.class)
    public GolemGroupRespondPolicy golemGroupRespondPolicy(ObjectProvider<StringRedisTemplate> redis,
                                                           GolemProperties properties) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && pingRedis(template)) {
            return new RedisGolemGroupRespondPolicy(template);
        }
        Path storeFile = Path.of(properties.getGroupRespondStorePath()).toAbsolutePath().normalize();
        return new FileGolemGroupRespondPolicy(storeFile);
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
