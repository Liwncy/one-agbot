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
    /** 处理入站对话的专用线程数；排队不丢。I/O 等待，默认 8。 */
    private int handlePoolSize = 8;
    /** 使用 OpenAPI chatStream，按段落/图片边界分多条推送。 */
    private boolean streamReply = true;
    /** 流式分片的最小字数（图链不受限）。 */
    private int streamMinChars = 16;
    /** 流式对话阻塞上限（毫秒），宜 ≥ snail-ai.open-api.chat-timeout-ms。 */
    private long streamTimeoutMs = 300_000L;
    /** 触发时附带同会话最近 inbound 的分钟数；≤0 关闭。 */
    private int contextWindowMinutes = 5;
    /** 时间窗内最多附带几条（不含本条）；≤0 关闭。 */
    private int contextMaxMessages = 10;
    /** 历史里最多再上传几张未进过模型的图；≤0 不传历史图。 */
    private int contextMaxImages = 3;
    /** 无会话短句补全（异常改写等）；未配 base-url/model 时不调用。 */
    private QuickLine quickLine = new QuickLine();

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

    public int getHandlePoolSize() {
        return handlePoolSize;
    }

    public void setHandlePoolSize(int handlePoolSize) {
        this.handlePoolSize = handlePoolSize;
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

    public int getContextWindowMinutes() {
        return contextWindowMinutes;
    }

    public void setContextWindowMinutes(int contextWindowMinutes) {
        this.contextWindowMinutes = contextWindowMinutes;
    }

    public int getContextMaxMessages() {
        return contextMaxMessages;
    }

    public void setContextMaxMessages(int contextMaxMessages) {
        this.contextMaxMessages = contextMaxMessages;
    }

    public int getContextMaxImages() {
        return contextMaxImages;
    }

    public void setContextMaxImages(int contextMaxImages) {
        this.contextMaxImages = contextMaxImages;
    }

    public QuickLine getQuickLine() {
        return quickLine;
    }

    public void setQuickLine(QuickLine quickLine) {
        this.quickLine = quickLine == null ? new QuickLine() : quickLine;
    }

    /**
     * OpenAI 兼容短句模型。异常改写只是其中一个 {@code task}。
     */
    public static class QuickLine {
        private boolean enabled = true;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int timeoutMs = 1_500;
        private int maxChars = 40;
        private String speaker = "小聪明儿";

        public boolean ready() {
            return enabled
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public String getSpeaker() {
            return speaker;
        }

        public void setSpeaker(String speaker) {
            this.speaker = speaker;
        }
    }
}
