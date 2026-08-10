package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.common.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地落盘：一行 {@code settings|account:groupId|{json}}。
 */
public class FileGolemGroupRespondPolicy implements GolemGroupRespondPolicy {
    private static final Logger log = LoggerFactory.getLogger(FileGolemGroupRespondPolicy.class);

    private final Path storeFile;
    private final Map<String, GolemGroupSettings> store = new ConcurrentHashMap<>();

    public FileGolemGroupRespondPolicy(Path storeFile) {
        this.storeFile = storeFile;
        load();
        log.info("Golem group settings using file={} count={} default=mention/followUpOff",
                storeFile.toAbsolutePath(), store.size());
    }

    @Override
    public GolemGroupSettings get(String accountId, String groupId) {
        return store.getOrDefault(GolemGroupRespondPolicy.key(accountId, groupId), GolemGroupSettings.defaults());
    }

    @Override
    public synchronized void save(String accountId, String groupId, GolemGroupSettings settings) {
        store.put(GolemGroupRespondPolicy.key(accountId, groupId),
                settings == null ? GolemGroupSettings.defaults() : settings);
        persist();
    }

    @Override
    public synchronized void ensureDefaults(String accountId, String groupId) {
        String key = GolemGroupRespondPolicy.key(accountId, groupId);
        if (!store.containsKey(key)) {
            store.put(key, GolemGroupSettings.defaults());
            persist();
        }
    }

    private void load() {
        if (!Files.isRegularFile(storeFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                // 新格式
                if (line.startsWith("settings|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length < 3) {
                        continue;
                    }
                    putParsed(parts[1].trim(), parts[2].trim());
                    continue;
                }
                // 兼容旧 mode| / rule| 分两行（跟聊默认关）
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) {
                    continue;
                }
                String kind = parts[0].trim().toLowerCase(Locale.ROOT);
                String key = parts[1].trim();
                String value = parts[2].trim();
                if (key.isEmpty()) {
                    continue;
                }
                GolemGroupSettings cur = store.getOrDefault(key, GolemGroupSettings.defaults());
                if ("mode".equals(kind)) {
                    GolemGroupRespondMode mode = GolemGroupRespondMode.parse(value);
                    if (mode != null) {
                        store.put(key, cur.withMode(mode));
                    }
                } else if ("rule".equals(kind)) {
                    GolemGroupRule rule = JsonUtils.fromJson(value, GolemGroupRule.class);
                    if (rule != null) {
                        store.put(key, cur.withRule(rule));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Load group settings failed: {}", e.getMessage());
        }
    }

    private void putParsed(String key, String json) {
        if (key.isEmpty()) {
            return;
        }
        GolemGroupSettings settings = JsonUtils.fromJson(json, GolemGroupSettings.class);
        if (settings != null) {
            store.put(key, settings);
        }
    }

    private void persist() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            store.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> lines.add("settings|" + e.getKey() + "|" + JsonUtils.toJson(e.getValue())));
            String body = String.join("\n", lines);
            if (!body.isEmpty()) {
                body = body + "\n";
            }
            Files.writeString(storeFile, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Persist group settings failed path={}", storeFile, e);
        }
    }
}
