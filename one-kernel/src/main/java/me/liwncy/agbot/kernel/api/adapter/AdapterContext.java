package me.liwncy.agbot.kernel.api.adapter;

import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;

import java.util.Collections;
import java.util.Map;

/**
 * 适配器初始化上下文。
 */
public record AdapterContext(
        AdapterRuntime runtime,
        Map<String, Object> config
) {
    public AdapterContext {
        if (config == null) {
            config = Collections.emptyMap();
        }
    }
}
