package me.liwncy.agbot.kernel.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 内核配置。
 */
@ConfigurationProperties(prefix = "agbot.kernel")
public class KernelProperties {
    /**
     * 入站消息最大年龄，超时丢弃（对齐 JBot ~60s）。
     */
    private Duration maxMessageAge = Duration.ofSeconds(60);
    private Duration conversationTtl = Duration.ofDays(30);

    public Duration getMaxMessageAge() {
        return maxMessageAge;
    }

    public void setMaxMessageAge(Duration maxMessageAge) {
        this.maxMessageAge = maxMessageAge;
    }

    public Duration getConversationTtl() {
        return conversationTtl;
    }

    public void setConversationTtl(Duration conversationTtl) {
        this.conversationTtl = conversationTtl;
    }
}
