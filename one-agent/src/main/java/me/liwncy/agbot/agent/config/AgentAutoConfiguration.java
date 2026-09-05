package me.liwncy.agbot.agent.config;

import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiChatClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiUserClient;
import com.aizuda.snail.ai.openapi.client.starter.SnailAiOpenApiAutoConfiguration;
import me.liwncy.agbot.agent.SnailAiAgentBridge;
import me.liwncy.agbot.agent.SnailAiOpenApiClient;
import me.liwncy.agbot.agent.quickline.QuickLineClient;
import me.liwncy.agbot.agent.roleplay.InMemoryRoleplaySessionStore;
import me.liwncy.agbot.agent.roleplay.RedisRoleplaySessionStore;
import me.liwncy.agbot.agent.roleplay.RoleplayCatalog;
import me.liwncy.agbot.agent.roleplay.RoleplayService;
import me.liwncy.agbot.agent.roleplay.RoleplaySessionStore;
import me.liwncy.agbot.agent.roleplay.mapper.RoleplayCharacterMapper;
import me.liwncy.agbot.common.mybatis.config.MybatisPlusConfig;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.agent.RoleplayCommands;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import me.liwncy.agbot.kernel.chatlog.ChatLogAutoConfiguration;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import me.liwncy.agbot.kernel.support.KernelAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 桥接自动配置：在官方 OpenAPI Client 就绪后装配 {@link AgentBridge}。
 */
@AutoConfiguration(after = {
        SnailAiOpenApiAutoConfiguration.class,
        KernelAutoConfiguration.class,
        MybatisPlusConfig.class,
        ChatLogAutoConfiguration.class
})
@EnableConfigurationProperties(AgbotAgentProperties.class)
@ConditionalOnProperty(prefix = "agbot.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({OpenApiUserClient.class, OpenApiChatClient.class, OpenApiAgentClient.class})
@MapperScan("me.liwncy.agbot.agent.roleplay.mapper")
public class AgentAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SnailAiOpenApiClient snailAiOpenApiClient(OpenApiUserClient userClient,
                                                     OpenApiChatClient chatClient,
                                                     OpenApiAgentClient agentClient) {
        return new SnailAiOpenApiClient(userClient, chatClient, agentClient);
    }

    @Bean
    @ConditionalOnMissingBean(RoleplaySessionStore.class)
    public RoleplaySessionStore roleplaySessionStore(ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null && pingRedis(template)) {
            return new RedisRoleplaySessionStore(template);
        }
        return new InMemoryRoleplaySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoleplayCatalog roleplayCatalog(RoleplayCharacterMapper mapper) {
        return new RoleplayCatalog(mapper);
    }

    @Bean
    @ConditionalOnMissingBean({RoleplayService.class, RoleplayCommands.class})
    public RoleplayService roleplayService(RoleplaySessionStore store,
                                           ConversationMapper conversationMapper,
                                           RoleplayCatalog catalog) {
        return new RoleplayService(store, conversationMapper, catalog);
    }

    @Bean
    @ConditionalOnMissingBean
    public QuickLineClient quickLineClient(AgbotAgentProperties properties) {
        return new QuickLineClient(properties);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "agentHandleExecutor")
    public ExecutorService agentHandleExecutor(AgbotAgentProperties properties) {
        int size = Math.max(1, Math.min(32, properties.getHandlePoolSize()));
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agbot-agent-" + seq.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        log.info("Agent handle pool size={}", size);
        return new ThreadPoolExecutor(
                size,
                size,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                factory);
    }

    @Bean
    @ConditionalOnMissingBean(AgentBridge.class)
    public AgentBridge agentBridge(SnailAiOpenApiClient client,
                                   ConversationMapper conversationMapper,
                                   AgbotAgentProperties properties,
                                   ObjectProvider<AdapterRuntime> runtimeProvider,
                                   RoleplayService roleplay,
                                   ObjectProvider<ChatLogService> chatLog,
                                   QuickLineClient quickLine,
                                   ExecutorService agentHandleExecutor) {
        return new SnailAiAgentBridge(client, conversationMapper, properties, runtimeProvider,
                roleplay, chatLog, quickLine, agentHandleExecutor);
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
            log.warn("Redis ping failed, roleplay fallback to memory: {}", e.getMessage());
            return false;
        }
    }
}
