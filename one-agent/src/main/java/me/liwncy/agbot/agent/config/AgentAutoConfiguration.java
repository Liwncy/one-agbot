package me.liwncy.agbot.agent.config;

import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiChatClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiUserClient;
import com.aizuda.snail.ai.openapi.client.starter.SnailAiOpenApiAutoConfiguration;
import me.liwncy.agbot.agent.SnailAiAgentBridge;
import me.liwncy.agbot.agent.SnailAiOpenApiClient;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import me.liwncy.agbot.kernel.chatlog.ChatLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Agent 桥接自动配置：在官方 OpenAPI Client 就绪后装配 {@link AgentBridge}。
 */
@AutoConfiguration(after = SnailAiOpenApiAutoConfiguration.class)
@EnableConfigurationProperties(AgbotAgentProperties.class)
@ConditionalOnProperty(prefix = "agbot.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({OpenApiUserClient.class, OpenApiChatClient.class, OpenApiAgentClient.class})
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SnailAiOpenApiClient snailAiOpenApiClient(OpenApiUserClient userClient,
                                                     OpenApiChatClient chatClient,
                                                     OpenApiAgentClient agentClient) {
        return new SnailAiOpenApiClient(userClient, chatClient, agentClient);
    }

    @Bean
    @ConditionalOnMissingBean(AgentBridge.class)
    public AgentBridge agentBridge(SnailAiOpenApiClient client,
                                   ConversationMapper conversationMapper,
                                   AgbotAgentProperties properties,
                                   ObjectProvider<AdapterRuntime> runtimeProvider,
                                   ObjectProvider<ChatLogService> chatLog) {
        return new SnailAiAgentBridge(client, conversationMapper, properties, runtimeProvider,
                chatLog.getIfAvailable());
    }
}
