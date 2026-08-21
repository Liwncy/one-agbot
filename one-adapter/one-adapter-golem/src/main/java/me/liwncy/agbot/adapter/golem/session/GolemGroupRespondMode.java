package me.liwncy.agbot.adapter.golem.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 群聊响应模式（按群持久化，主人指令切换）。
 */
public enum GolemGroupRespondMode {
    /** 要 @ / 点名（或短窗口跟聊）才回 */
    MENTION,
    /** 会话开启后群内全量响应 */
    FULL,
    /** 限定规则：白名单用户 / 关键词（点名仍可触发） */
    RULE,
    /** 按概率随机插话 */
    RANDOM,
    /** 智能：点名 ∪ 规则 ∪ 随机（共用跟聊/概率/白名单/关键词配置） */
    SMART;

    public String label() {
        return switch (this) {
            case MENTION -> "点名";
            case FULL -> "全量";
            case RULE -> "规则";
            case RANDOM -> "随机";
            case SMART -> "智能";
        };
    }

    public String tip() {
        return switch (this) {
            case MENTION -> "点到我或引用我才回（跟聊窗口内可免）";
            case FULL -> "群里说话我都接；会话忙时只接点名";
            case RULE -> "只听白名单或关键词（点到我也能触发）";
            case RANDOM -> "按概率偶尔接一句；会话忙时不随机";
            case SMART -> "点名/跟聊、规则命中或按概率都会接；会话忙时不随机";
        };
    }

    @JsonValue
    public String jsonValue() {
        return name();
    }

    @JsonCreator
    public static GolemGroupRespondMode fromJson(String raw) {
        GolemGroupRespondMode mode = parse(raw);
        return mode == null ? MENTION : mode;
    }

    public static GolemGroupRespondMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "mention", "点名", "@", "要@", "部分" -> MENTION;
            case "full", "全量", "全部", "全都回", "all" -> FULL;
            case "rule", "规则", "限定" -> RULE;
            case "random", "随机", "随机模式", "概率" -> RANDOM;
            case "smart", "智能", "智能模式" -> SMART;
            default -> {
                try {
                    yield GolemGroupRespondMode.valueOf(t.toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    yield null;
                }
            }
        };
    }

    public static GolemGroupRespondMode defaultOf(boolean groupRequireMention) {
        return groupRequireMention ? MENTION : FULL;
    }
}
