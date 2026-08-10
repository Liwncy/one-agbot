package me.liwncy.agbot.adapter.golem.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件持久化会话激活（Redis 不可用时）。
 */
public class FileGolemSessionActivation implements GolemSessionActivation {
    private static final Logger log = LoggerFactory.getLogger(FileGolemSessionActivation.class);

    private final Path storeFile;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public FileGolemSessionActivation(Path storeFile) {
        this.storeFile = storeFile;
        load();
        log.info("Golem session activation using file={} activeCount={}", storeFile, active.size());
    }

    @Override
    public boolean isActive(String accountId, String peerKey) {
        return active.contains(GolemSessionActivation.redisMember(accountId, peerKey));
    }

    @Override
    public void activate(String accountId, String peerKey) {
        if (active.add(GolemSessionActivation.redisMember(accountId, peerKey))) {
            persist();
        }
    }

    @Override
    public void deactivate(String accountId, String peerKey) {
        if (active.remove(GolemSessionActivation.redisMember(accountId, peerKey))) {
            persist();
        }
    }

    private void load() {
        if (!Files.isRegularFile(storeFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                String v = line == null ? "" : line.trim();
                if (!v.isEmpty() && !v.startsWith("#")) {
                    active.add(v);
                }
            }
        } catch (IOException e) {
            log.warn("Load session activation file failed: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Set<String> snapshot = new LinkedHashSet<>(active);
            Files.write(storeFile, snapshot.isEmpty() ? Collections.emptyList() : snapshot.stream().sorted().toList(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Persist session activation failed path={}", storeFile, e);
        }
    }
}
