package me.liwncy.agbot.adapter.golem.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群聊启停开关（默认开启；停用后记入 disabled 集合）。
 * <p>MVP 进程内内存，重启后恢复为全开。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemGroupGate {
    private final Set<String> disabledGroups = ConcurrentHashMap.newKeySet();

    public boolean isEnabled(String accountId, String groupId) {
        return !disabledGroups.contains(key(accountId, groupId));
    }

    public void enable(String accountId, String groupId) {
        disabledGroups.remove(key(accountId, groupId));
    }

    public void disable(String accountId, String groupId) {
        disabledGroups.add(key(accountId, groupId));
    }

    private static String key(String accountId, String groupId) {
        return (accountId == null || accountId.isBlank() ? "default" : accountId)
                + ":"
                + (groupId == null ? "" : groupId);
    }
}
