package me.liwncy.agbot.agent.roleplay;

import java.util.List;

/**
 * 一条角色定义（目录在代码里，会话绑定在 Redis）。
 */
public record RoleplayCharacter(
        String id,
        String name,
        List<String> triggers,
        String instruction,
        String ack
) {
}
