package me.liwncy.agbot.adapter.golem.session;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
import java.util.List;

/**
 * 单个群的响应配置（一份 JSON，Redis Hash / 本地文件共用）。
 * <p>默认：点名、跟聊关、随机概率 15%。</p>
 */
public record GolemGroupSettings(
        GolemGroupRespondMode mode,
        int followUpSeconds,
        int replyChancePercent,
        List<String> userIds,
        List<String> keywords
) {
    public static final int DEFAULT_REPLY_CHANCE = 15;

    public static final GolemGroupSettings DEFAULTS = new GolemGroupSettings(
            GolemGroupRespondMode.MENTION, 0, DEFAULT_REPLY_CHANCE, List.of(), List.of());

    public GolemGroupSettings {
        if (mode == null) {
            mode = GolemGroupRespondMode.MENTION;
        }
        followUpSeconds = Math.max(0, followUpSeconds);
        replyChancePercent = Math.min(100, Math.max(0, replyChancePercent));
        GolemGroupRule rule = new GolemGroupRule(userIds, keywords);
        userIds = rule.userIds();
        keywords = rule.keywords();
    }

    public static GolemGroupSettings defaults() {
        return DEFAULTS;
    }

    @JsonIgnore
    public Duration followUpWindow() {
        return followUpSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(followUpSeconds);
    }

    @JsonIgnore
    public boolean followUpEnabled() {
        return followUpSeconds > 0;
    }

    @JsonIgnore
    public GolemGroupRule rule() {
        return new GolemGroupRule(userIds, keywords);
    }

    public GolemGroupSettings withMode(GolemGroupRespondMode next) {
        return new GolemGroupSettings(next, followUpSeconds, replyChancePercent, userIds, keywords);
    }

    public GolemGroupSettings withFollowUpSeconds(int seconds) {
        return new GolemGroupSettings(mode, Math.max(0, seconds), replyChancePercent, userIds, keywords);
    }

    public GolemGroupSettings withReplyChancePercent(int percent) {
        int p = Math.min(100, Math.max(0, percent));
        return new GolemGroupSettings(mode, followUpSeconds, p, userIds, keywords);
    }

    public GolemGroupSettings withRule(GolemGroupRule rule) {
        GolemGroupRule r = rule == null ? GolemGroupRule.EMPTY : rule;
        return new GolemGroupSettings(mode, followUpSeconds, replyChancePercent, r.userIds(), r.keywords());
    }

    public String followUpLabel() {
        return followUpEnabled() ? ("跟聊 " + followUpSeconds + " 秒") : "跟聊关";
    }

    public String chanceLabel() {
        return "概率 " + replyChancePercent + "%";
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("模式「").append(mode.label()).append("」——").append(mode.tip());
        sb.append("；").append(followUpLabel());
        if (mode == GolemGroupRespondMode.RANDOM || mode == GolemGroupRespondMode.SMART) {
            sb.append("；").append(chanceLabel());
        }
        if (mode == GolemGroupRespondMode.RULE) {
            sb.append("；").append(rule().summary());
        }
        return sb.toString();
    }
}
