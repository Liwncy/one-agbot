package me.liwncy.agbot.kernel.support;

import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import me.liwncy.agbot.kernel.api.session.ConversationTurnGuard;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@EnableConfigurationProperties(KernelProperties.class)
public class KernelAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KernelAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ConversationTurnGuard.class)
    public ConversationTurnGuard conversationTurnGuard() {
        return new ConversationTurnGuard();
    }

    @Bean
    @ConditionalOnMissingBean(ConversationMapper.class)
    public ConversationMapper conversationMapper(ObjectProvider<StringRedisTemplate> redis,
                                                 KernelProperties properties) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && pingRedis(template)) {
            log.info("ConversationMapper using Redis");
            return new RedisConversationMapper(template, properties.getConversationTtl());
        }
        log.warn("ConversationMapper using in-memory store (Redis unavailable or auth failed)");
        return new InMemoryConversationMapper();
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
            log.warn("Redis ping failed, fallback to memory: {}", e.getMessage());
            return false;
        }
    }

    @Bean
    @ConditionalOnMissingBean(AdapterRuntime.class)
    public AdapterRuntime adapterRuntime(AgentBridge agentBridge,
                                         KernelProperties properties,
                                         ObjectProvider<ChatLogService> chatLog) {
        return new DefaultAdapterRuntime(agentBridge, properties, chatLog.getIfAvailable());
    }

    @Bean
    public AdapterLifecycle adapterLifecycle(AdapterRuntime runtime, ObjectProvider<List<ChatAdapter>> adapters) {
        return new AdapterLifecycle(runtime, adapters);
    }
}
