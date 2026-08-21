package me.liwncy.agbot.kernel.api.session;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一会话同时只占一个 Agent 回合。通道可用来在忙时关掉随机插话。
 */
public class ConversationTurnGuard {
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public boolean isBusy(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return false;
        }
        return inFlight.containsKey(sessionKey);
    }

    public boolean tryOccupy(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return true;
        }
        return inFlight.putIfAbsent(sessionKey, Boolean.TRUE) == null;
    }

    public void release(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        inFlight.remove(sessionKey);
    }
}
