package me.liwncy.agbot.kernel.support;

import me.liwncy.agbot.kernel.api.adapter.AdapterContext;
import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Map;

/**
 * 应用就绪时 init/start 全部适配器，关闭时 stop。
 */
public class AdapterLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AdapterLifecycle.class);

    private final AdapterRuntime runtime;
    private final ObjectProvider<List<ChatAdapter>> adapters;

    public AdapterLifecycle(AdapterRuntime runtime, ObjectProvider<List<ChatAdapter>> adapters) {
        this.runtime = runtime;
        this.adapters = adapters;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        List<ChatAdapter> list = adapters.getIfAvailable(List::of);
        for (ChatAdapter adapter : list) {
            try {
                runtime.register(adapter);
                adapter.init(new AdapterContext(runtime, Map.of()));
                adapter.start();
                log.info("Adapter started: {}", adapter.platform());
            } catch (Exception e) {
                log.error("Adapter start failed: {}", adapter.platform(), e);
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onClose() {
        List<ChatAdapter> list = adapters.getIfAvailable(List::of);
        for (ChatAdapter adapter : list) {
            try {
                adapter.stop();
            } catch (Exception e) {
                log.warn("Adapter stop failed: {}", adapter.platform(), e);
            }
        }
    }
}
