package me.liwncy.agbot.adapter.golem.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 本地文件持久化群开关（Redis 不可用时的兜底）。
 */
public class FileGolemGroupGate implements GolemGroupGate {
    private static final Logger log = LoggerFactory.getLogger(FileGolemGroupGate.class);

    private final Path storeFile;
    private final Set<String> disabledGroups = ConcurrentHashMap.newKeySet();

    public FileGolemGroupGate(Path storeFile) {
        this.storeFile = storeFile;
        load();
        log.info("Golem group gate using file store path={} disabledCount={}",
                storeFile.toAbsolutePath(), disabledGroups.size());
    }

    @Override
    public boolean isEnabled(String accountId, String groupId) {
        return !disabledGroups.contains(GolemGroupGate.key(accountId, groupId));
    }

    @Override
    public synchronized void enable(String accountId, String groupId) {
        if (disabledGroups.remove(GolemGroupGate.key(accountId, groupId))) {
            persist();
        }
    }

    @Override
    public synchronized void disable(String accountId, String groupId) {
        if (disabledGroups.add(GolemGroupGate.key(accountId, groupId))) {
            persist();
        }
    }

    private void load() {
        if (!Files.isRegularFile(storeFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                String key = line == null ? "" : line.trim();
                if (!key.isEmpty() && !key.startsWith("#")) {
                    disabledGroups.add(key);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load group gate file {}: {}", storeFile, e.getMessage());
        }
    }

    private void persist() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Set<String> snapshot = new LinkedHashSet<>(disabledGroups);
            String body = snapshot.stream().sorted().collect(Collectors.joining("\n"));
            if (!body.isEmpty()) {
                body = body + "\n";
            }
            Files.writeString(storeFile, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to persist group gate file {}", storeFile, e);
        }
    }
}
