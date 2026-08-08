package me.liwncy.agbot.agent.config;

import com.aizuda.snail.ai.agent.starter.EnableSnailAiAgent;
import com.aizuda.snail.ai.openapi.client.starter.EnableSnailAiOpenApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Snail AI 自动配置（对齐 RuoYi {@code org.dromara.common.ai.config.SnailAiConfig}）。
 * <p>{@code snail-ai.enabled=true} 时注册 Agent Client（gRPC 执行器），供 Server 调度对话。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "snail-ai", name = "enabled", havingValue = "true")
@EnableSnailAiAgent
@EnableSnailAiOpenApi
public class SnailAiConfig {
}
