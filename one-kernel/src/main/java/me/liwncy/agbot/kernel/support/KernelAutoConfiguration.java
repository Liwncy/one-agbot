package me.liwncy.agbot.kernel.support;

import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(KernelProperties.class)
public class KernelAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(ConversationMapper.class)
    public ConversationMapper redisConversationMapper(StringRedisTemplate redis, KernelProperties properties) {
        return new RedisConversationMapper(redis, properties.getConversationTtl());
    }

    @Bean
    @ConditionalOnMissingBean(ConversationMapper.class)
    public ConversationMapper inMemoryConversationMapper() {
        return new InMemoryConversationMapper();
    }

    @Bean
    @ConditionalOnMissingBean(AdapterRuntime.class)
    public AdapterRuntime adapterRuntime(AgentBridge agentBridge, KernelProperties properties) {
        return new DefaultAdapterRuntime(agentBridge, properties);
    }

    @Bean
    public AdapterLifecycle adapterLifecycle(AdapterRuntime runtime, ObjectProvider<List<ChatAdapter>> adapters) {
        return new AdapterLifecycle(runtime, adapters);
    }
}
