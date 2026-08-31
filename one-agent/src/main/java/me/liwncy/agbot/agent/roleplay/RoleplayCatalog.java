package me.liwncy.agbot.agent.roleplay;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import me.liwncy.agbot.agent.roleplay.domain.RoleplayCharacterEntity;
import me.liwncy.agbot.agent.roleplay.mapper.RoleplayCharacterMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 角色目录，读 {@code agbot_roleplay_character}。
 */
public class RoleplayCatalog {
    public static final String NORMAL_ID = "normal";
    public static final String STATUS_ACTIVE = "active";

    private static final Logger log = LoggerFactory.getLogger("agbot.agent");
    private static final List<String> EXIT_COMMANDS = List.of("退出角色", "取消扮演", "不当了", "别演了");
    private static final List<String> NAME_PREFIXES = List.of("扮演", "当", "换成");

    private final RoleplayCharacterMapper mapper;

    public RoleplayCatalog(RoleplayCharacterMapper mapper) {
        this.mapper = mapper;
    }

    public RoleplayCharacter get(String roleKey) {
        if (roleKey == null || roleKey.isBlank() || NORMAL_ID.equals(roleKey)) {
            return null;
        }
        try {
            RoleplayCharacterEntity row = mapper.selectOne(Wrappers.<RoleplayCharacterEntity>lambdaQuery()
                    .eq(RoleplayCharacterEntity::getRoleKey, roleKey)
                    .eq(RoleplayCharacterEntity::getStatus, STATUS_ACTIVE)
                    .last("LIMIT 1"));
            return toCharacter(row);
        } catch (Exception e) {
            log.warn("Load roleplay character failed roleKey={}: {}", roleKey, e.getMessage());
            return null;
        }
    }

    /**
     * 整句口令对上才算。对不上返回 null。
     */
    public String matchCommandId(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String key = normalize(command);
        if (isExit(key)) {
            return NORMAL_ID;
        }
        return buildTriggerMap().get(key);
    }

    public static boolean isQuery(String command) {
        String key = normalize(command);
        return "当前角色".equals(key)
                || "现在演谁".equals(key)
                || "你在演谁".equals(key)
                || "演的谁".equals(key);
    }

    /**
     * 新建角色。名字已被占用时返回说明，成功返回 null。
     */
    public String create(String nameRaw, String instructionRaw) {
        String name = nameRaw == null ? "" : nameRaw.trim();
        String instruction = instructionRaw == null ? "" : instructionRaw.trim();
        if (name.isEmpty()) {
            return "名字空了";
        }
        if (name.length() > 16) {
            return "名字短一点";
        }
        if (instruction.isEmpty()) {
            return "演法写在名字后面";
        }
        if (instruction.length() > 4000) {
            return "演法短一点";
        }
        String roleKey = name;
        if (roleKey.length() > 32 || NORMAL_ID.equalsIgnoreCase(roleKey) || isReservedName(name)) {
            return "这名字不行";
        }
        if (nameTaken(name, roleKey)) {
            return "已经有这个了";
        }
        RoleplayCharacterEntity row = new RoleplayCharacterEntity();
        row.setRoleKey(roleKey);
        row.setName(name);
        row.setTriggers("");
        row.setInstruction(instruction);
        row.setAck("好，" + name + "。");
        row.setStatus(STATUS_ACTIVE);
        row.setSortNo(200);
        try {
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("Create roleplay character failed name={}: {}", name, e.getMessage());
            return "没加上，再试下";
        }
        log.info("Roleplay character created roleKey={} name={}", roleKey, name);
        return null;
    }

    private boolean nameTaken(String name, String roleKey) {
        String want = normalize(name);
        String keyWant = normalize(roleKey);
        for (RoleplayCharacterEntity row : listAll()) {
            if (row == null) {
                continue;
            }
            if (keyWant.equals(normalize(row.getRoleKey())) || want.equals(normalize(row.getName()))) {
                return true;
            }
            for (String alias : parseTriggers(row.getTriggers())) {
                if (want.equals(normalize(alias))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<RoleplayCharacterEntity> listAll() {
        try {
            return mapper.selectList(Wrappers.<RoleplayCharacterEntity>lambdaQuery()
                    .orderByAsc(RoleplayCharacterEntity::getSortNo)
                    .orderByAsc(RoleplayCharacterEntity::getId));
        } catch (Exception e) {
            log.warn("Load roleplay catalog failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isReservedName(String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return true;
        }
        for (String reserved : List.of(
                "正常", "角色", "扮演", "当", "换成", "加角色", "增加角色", "新增角色",
                "退出角色", "取消扮演", "不当了", "别演了", "当前角色", "小聪明儿", "normal")) {
            if (key.equals(normalize(reserved))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> buildTriggerMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (RoleplayCharacterEntity row : listActive()) {
            RoleplayCharacter character = toCharacter(row);
            if (character == null) {
                continue;
            }
            registerName(map, character.name(), character.id());
            for (String alias : character.triggers()) {
                registerName(map, alias, character.id());
            }
        }
        return map;
    }

    private List<RoleplayCharacterEntity> listActive() {
        try {
            return mapper.selectList(Wrappers.<RoleplayCharacterEntity>lambdaQuery()
                    .eq(RoleplayCharacterEntity::getStatus, STATUS_ACTIVE)
                    .orderByAsc(RoleplayCharacterEntity::getSortNo)
                    .orderByAsc(RoleplayCharacterEntity::getId));
        } catch (Exception e) {
            log.warn("Load roleplay catalog failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static RoleplayCharacter toCharacter(RoleplayCharacterEntity row) {
        if (row == null || row.getRoleKey() == null || row.getRoleKey().isBlank()) {
            return null;
        }
        String name = row.getName() == null ? "" : row.getName().trim();
        if (name.isEmpty()) {
            return null;
        }
        return new RoleplayCharacter(
                row.getRoleKey().trim(),
                name,
                parseTriggers(row.getTriggers()),
                row.getInstruction() == null ? "" : row.getInstruction(),
                row.getAck() == null ? "" : row.getAck().trim());
    }

    private static List<String> parseTriggers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,，;；]")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isExit(String normalized) {
        for (String exit : EXIT_COMMANDS) {
            if (normalize(exit).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static void registerName(Map<String, String> map, String name, String id) {
        registerTrigger(map, name, id);
        for (String prefix : NAME_PREFIXES) {
            registerTrigger(map, prefix + name, id);
        }
    }

    private static void registerTrigger(Map<String, String> map, String raw, String id) {
        String key = normalize(raw);
        if (!key.isEmpty()) {
            map.putIfAbsent(key, id);
        }
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
