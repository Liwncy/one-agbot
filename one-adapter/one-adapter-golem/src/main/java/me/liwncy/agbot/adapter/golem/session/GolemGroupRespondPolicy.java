package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.kernel.api.message.MsgInfo;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 按群响应配置：模式 / 跟聊秒数 / 随机概率 / 规则，合并存一份。
 */
public interface GolemGroupRespondPolicy {

    GolemGroupSettings get(String accountId, String groupId);

    void save(String accountId, String groupId, GolemGroupSettings settings);

    void ensureDefaults(String accountId, String groupId);

    default GolemGroupRespondMode getMode(String accountId, String groupId) {
        return get(accountId, groupId).mode();
    }

    default void setMode(String accountId, String groupId, GolemGroupRespondMode mode) {
        save(accountId, groupId, get(accountId, groupId).withMode(mode));
    }

    default GolemGroupRule getRule(String accountId, String groupId) {
        return get(accountId, groupId).rule();
    }

    default void setRule(String accountId, String groupId, GolemGroupRule rule) {
        save(accountId, groupId, get(accountId, groupId).withRule(rule));
    }

    default Duration getFollowUpWindow(String accountId, String groupId) {
        return get(accountId, groupId).followUpWindow();
    }

    default void setFollowUpSeconds(String accountId, String groupId, int seconds) {
        save(accountId, groupId, get(accountId, groupId).withFollowUpSeconds(seconds));
    }

    default void setReplyChancePercent(String accountId, String groupId, int percent) {
        save(accountId, groupId, get(accountId, groupId).withReplyChancePercent(percent));
    }

    /**
     * 当前消息是否应按群策略放行（私聊请勿调用）。
     * 白名单用户、关键词命中任何模式都直接放过。
     * 点名含 @/昵称/引用及跟聊窗；随机另看概率。
     */
    default boolean allows(MsgInfo msg, boolean mentioned, boolean mentionWindowActive) {
        return allows(msg, mentioned, mentionWindowActive, false);
    }

    default boolean allows(MsgInfo msg, boolean mentioned, boolean mentionWindowActive,
                           boolean conversationBusy) {
        if (msg == null || msg.isPrivateChat()) {
            return true;
        }
        GolemGroupSettings settings = get(msg.accountId(), msg.groupId());
        boolean chance = !conversationBusy && hitsChance(settings);
        boolean named = mentioned || mentionWindowActive;
        boolean listed = matchesRule(settings, msg);
        return switch (settings.mode()) {
            case FULL -> !conversationBusy || named || listed;
            case MENTION, RULE -> named || listed;
            case RANDOM -> chance || listed;
            case SMART -> named || chance || listed;
        };
    }

    private static boolean matchesRule(GolemGroupSettings settings, MsgInfo msg) {
        GolemGroupRule rule = settings.rule();
        return rule.matchesUser(msg.userId()) || rule.matchesKeyword(msg.msg());
    }

    private static boolean hitsChance(GolemGroupSettings settings) {
        int chance = settings.replyChancePercent();
        return chance > 0 && ThreadLocalRandom.current().nextInt(100) < chance;
    }

    static String key(String accountId, String groupId) {
        return (accountId == null ? "" : accountId.trim())
                + ":"
                + (groupId == null ? "" : groupId.trim());
    }
}
