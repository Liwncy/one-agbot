package me.liwncy.agbot.common.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 启用 Spring AI MCP Server。工具由各模块 {@code @McpTool} Bean 注册到同一 {@code /mcp}。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(McpAutoConfiguration.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("MCP server enabled endpoint=/mcp (bind internally; SnailAI sai_mcp_server)");
    }
}
