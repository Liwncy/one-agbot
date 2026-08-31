package me.liwncy.agbot.adapter.golem.session;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用户白名单 + 关键词（命中任一即直接放过；@ 另计）。
 */
public record GolemGroupRule(List<String> userIds, List<String> keywords) {

    public static final GolemGroupRule EMPTY = new GolemGroupRule(List.of(), List.of());

    public GolemGroupRule {
        userIds = normalizeIds(userIds);
        keywords = normalizeKeywords(keywords);
    }

    public boolean isEmpty() {
        return userIds.isEmpty() && keywords.isEmpty();
    }

    public boolean matchesUser(String userId) {
        if (userId == null || userId.isBlank() || userIds.isEmpty()) {
            return false;
        }
        String id = userId.trim();
        for (String allow : userIds) {
            if (allow.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesKeyword(String text) {
        if (text == null || text.isBlank() || keywords.isEmpty()) {
            return false;
        }
        String body = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (!kw.isEmpty() && body.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public String summary() {
        if (isEmpty()) {
            return "还没配规则，先「规则 用户 …」或「规则 关键词 …」";
        }
        StringBuilder sb = new StringBuilder();
        if (!userIds.isEmpty()) {
            sb.append("用户 ").append(String.join("、", userIds));
        }
        if (!keywords.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append("关键词 ").append(String.join("、", keywords));
        }
        return sb.toString();
    }

    public GolemGroupRule withUsers(List<String> users) {
        return new GolemGroupRule(users, keywords);
    }

    public GolemGroupRule withKeywords(List<String> kws) {
        return new GolemGroupRule(userIds, kws);
    }

    private static List<String> normalizeIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : raw) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(new ArrayList<>(out));
    }

    private static List<String> normalizeKeywords(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String kw : raw) {
            if (kw != null && !kw.isBlank()) {
                out.add(kw.trim());
            }
        }
        return List.copyOf(new ArrayList<>(out));
    }
}
