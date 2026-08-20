package me.liwncy.agbot.kernel.chatlog;

import me.liwncy.agbot.common.json.JsonUtils;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 从 extra 抽出可落库的适配器私有字段，去掉 base64 / 过长文本。
 */
final class ChatLogExtras {

    static final int MAX_CONTENT = 4000;
    static final int MAX_EXTRA_JSON = 8000;
    static final int MAX_EXTRA_VALUE = 500;

    private static final Set<String> SKIP_KEYS = Set.of(
            ChannelExtraKeys.MEDIA_BASE64
    );

    private ChatLogExtras() {
    }

    static String toJson(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        Map<String, Object> slim = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            String key = entry.getKey();
            if (key == null || SKIP_KEYS.contains(key) || entry.getValue() == null) {
                continue;
            }
            slim.put(key, clipValue(entry.getValue()));
        }
        if (slim.isEmpty()) {
            return null;
        }
        String json = JsonUtils.toJson(slim);
        return clip(json, MAX_EXTRA_JSON);
    }

    static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private static Object clipValue(Object value) {
        if (value instanceof String text) {
            return clip(text, MAX_EXTRA_VALUE);
        }
        String json = JsonUtils.toJson(value);
        if (json.length() <= MAX_EXTRA_VALUE) {
            return value;
        }
        return clip(json, MAX_EXTRA_VALUE);
    }
}
