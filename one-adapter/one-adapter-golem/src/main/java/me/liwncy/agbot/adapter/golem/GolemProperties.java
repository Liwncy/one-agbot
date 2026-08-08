package me.liwncy.agbot.adapter.golem;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
    /** 群聊是否仅在被 @ / 点名时才回复（私聊不受影响） */
    private boolean groupRequireMention = true;

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

    public boolean isGroupRequireMention() {
        return groupRequireMention;
    }

    public void setGroupRequireMention(boolean groupRequireMention) {
        this.groupRequireMention = groupRequireMention;
    }
}
