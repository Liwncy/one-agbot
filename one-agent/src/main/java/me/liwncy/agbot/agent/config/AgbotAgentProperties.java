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
    /** 使用 OpenAPI chatStream，按段落/图片边界分多条推送。 */
    private boolean streamReply = true;
    /** 流式分片的最小字数（图链不受限）。 */
    private int streamMinChars = 16;
    /** 流式对话阻塞上限（毫秒），宜 ≥ snail-ai.open-api.chat-timeout-ms。 */
    private long streamTimeoutMs = 300_000L;
    /** 群聊接话时是否在正文前附带近期通道记录。 */
    private boolean contextEnabled = true;
    /** 上下文时间窗（分钟）。上次出站早于此时当冷启动。 */
    private int contextWindowMinutes = 30;
    /** 上下文最多条数（不含本条）。 */
    private int contextMaxRows = 20;

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

    public boolean isStreamReply() {
        return streamReply;
    }

    public void setStreamReply(boolean streamReply) {
        this.streamReply = streamReply;
    }

    public int getStreamMinChars() {
        return streamMinChars;
    }

    public void setStreamMinChars(int streamMinChars) {
        this.streamMinChars = streamMinChars;
    }

    public long getStreamTimeoutMs() {
        return streamTimeoutMs;
    }

    public void setStreamTimeoutMs(long streamTimeoutMs) {
        this.streamTimeoutMs = streamTimeoutMs;
    }

    public boolean isContextEnabled() {
        return contextEnabled;
    }

    public void setContextEnabled(boolean contextEnabled) {
        this.contextEnabled = contextEnabled;
    }

    public int getContextWindowMinutes() {
        return contextWindowMinutes;
    }

    public void setContextWindowMinutes(int contextWindowMinutes) {
        this.contextWindowMinutes = contextWindowMinutes;
    }

    public int getContextMaxRows() {
        return contextMaxRows;
    }

    public void setContextMaxRows(int contextMaxRows) {
        this.contextMaxRows = contextMaxRows;
    }
}
