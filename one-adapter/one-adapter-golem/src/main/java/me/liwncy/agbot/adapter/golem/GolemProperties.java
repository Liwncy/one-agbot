package me.liwncy.agbot.adapter.golem;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Golem 适配器配置（对齐 xchatbot 的 WECHAT_* 环境变量语义）。
 */
@ConfigurationProperties(prefix = "agbot.adapter.golem")
public class GolemProperties {
    /** 是否启用；关闭时不注册 Adapter / Webhook。 */
    private boolean enabled;
    /** Golem OpenAPI 根地址，如 http://127.0.0.1:7080 */
    private String apiBaseUrl = "http://127.0.0.1:7080";
    /** Webhook HMAC token；为空则跳过验签 */
    private String webhookToken = "";
    /** 机器人自身 wxid，用于忽略回环与群 @ 识别 */
    private String botWechatId = "";
    /** 机器人昵称，用于群聊 @昵称 识别 */
    private String botWechatName = "";
    /** 机器人主人 wxid，可在群内发送启停指令 */
    private String ownerWechatId = "";
    /** 是否处理公众号/官方消息 */
    private boolean allowOfficial;
    /**
     * 会话默认不激活：未收到「开始/开机」等指令前不进 Agent（不自动注册用户/建会话）。
     * 私聊本人可开；群聊仅主人可开。
     */
    private boolean sessionRequireActivation = true;
    /**
     * 兼容字段：历史默认「要 @」。真正默认见 {@code GolemGroupSettings}（点名 + 跟聊关），
     * 按群写在 Redis {@code agbot:golem:group-settings}。
     */
    private boolean groupRequireMention = true;
    /**
     * 已废弃：跟聊秒数改为按群 {@code followUpSeconds}（默认 0）。
     * 保留配置项以免旧 yml 报错。
     */
    private Duration groupActivationWindow = Duration.ZERO;
    /** Redis 不可用时，群开关落盘路径（一行一个 accountId:groupId） */
    private String groupGateStorePath = "./data/golem/group-disabled.txt";
    /**
     * 按群响应模式落盘（Redis 不可用时）。
     * 行格式：{@code mode|account:groupId|FULL} / {@code rule|account:groupId|{json}}
     */
    private String groupRespondStorePath = "./data/golem/group-respond.txt";
    /** 是否把 PLATFORM 媒体下载升级为 FILE/BASE64 */
    private boolean mediaResolveEnabled = true;
    /** 会话激活落盘（Redis 不可用时） */
    private String sessionActiveStorePath = "./data/golem/session-active.txt";
    /** 媒体落盘目录（prefer FILE 时） */
    private String mediaStorePath = "./data/golem/media";
    /** 解析后优先形态：FILE 或 BASE64 */
    private String mediaPreferForm = "FILE";
    /** 单文件最大字节，超出则保留 PLATFORM */
    private long mediaMaxBytes = 20L * 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public String getBotWechatId() {
        return botWechatId;
    }

    public void setBotWechatId(String botWechatId) {
        this.botWechatId = botWechatId;
    }

    public String getBotWechatName() {
        return botWechatName;
    }

    public void setBotWechatName(String botWechatName) {
        this.botWechatName = botWechatName;
    }

    public String getOwnerWechatId() {
        return ownerWechatId;
    }

    public void setOwnerWechatId(String ownerWechatId) {
        this.ownerWechatId = ownerWechatId;
    }

    public boolean isAllowOfficial() {
        return allowOfficial;
    }

    public void setAllowOfficial(boolean allowOfficial) {
        this.allowOfficial = allowOfficial;
    }

    public boolean isSessionRequireActivation() {
        return sessionRequireActivation;
    }

    public void setSessionRequireActivation(boolean sessionRequireActivation) {
        this.sessionRequireActivation = sessionRequireActivation;
    }

    public boolean isGroupRequireMention() {
        return groupRequireMention;
    }

    public void setGroupRequireMention(boolean groupRequireMention) {
        this.groupRequireMention = groupRequireMention;
    }

    public String getSessionActiveStorePath() {
        return sessionActiveStorePath;
    }

    public void setSessionActiveStorePath(String sessionActiveStorePath) {
        this.sessionActiveStorePath = sessionActiveStorePath;
    }

    public String getGroupGateStorePath() {
        return groupGateStorePath;
    }

    public void setGroupGateStorePath(String groupGateStorePath) {
        this.groupGateStorePath = groupGateStorePath;
    }

    public String getGroupRespondStorePath() {
        return groupRespondStorePath;
    }

    public void setGroupRespondStorePath(String groupRespondStorePath) {
        this.groupRespondStorePath = groupRespondStorePath;
    }

    public Duration getGroupActivationWindow() {
        return groupActivationWindow;
    }

    public void setGroupActivationWindow(Duration groupActivationWindow) {
        this.groupActivationWindow = groupActivationWindow;
    }

    public boolean isMediaResolveEnabled() {
        return mediaResolveEnabled;
    }

    public void setMediaResolveEnabled(boolean mediaResolveEnabled) {
        this.mediaResolveEnabled = mediaResolveEnabled;
    }

    public String getMediaStorePath() {
        return mediaStorePath;
    }

    public void setMediaStorePath(String mediaStorePath) {
        this.mediaStorePath = mediaStorePath;
    }

    public String getMediaPreferForm() {
        return mediaPreferForm;
    }

    public void setMediaPreferForm(String mediaPreferForm) {
        this.mediaPreferForm = mediaPreferForm;
    }

    public long getMediaMaxBytes() {
        return mediaMaxBytes;
    }

    public void setMediaMaxBytes(long mediaMaxBytes) {
        this.mediaMaxBytes = mediaMaxBytes;
    }
}
