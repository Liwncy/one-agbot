package me.liwncy.agbot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关 AgentBridge 业务配置。
 * <p>连接地址 / appId / token 使用官方 {@code snail-ai.*}，与 RuoYi 一致。</p>
 */
@ConfigurationProperties(prefix = "agbot.agent")
public class AgbotAgentProperties {
    private boolean enabled = true;
    private long defaultAgentId = 1L;
    private boolean asyncHandled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(long defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public boolean isAsyncHandled() {
        return asyncHandled;
    }

    public void setAsyncHandled(boolean asyncHandled) {
        this.asyncHandled = asyncHandled;
    }
}
