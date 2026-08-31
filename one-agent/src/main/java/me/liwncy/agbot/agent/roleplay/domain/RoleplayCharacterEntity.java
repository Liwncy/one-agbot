package me.liwncy.agbot.agent.roleplay.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import me.liwncy.agbot.common.mybatis.core.domain.BaseEntity;

/**
 * 角色目录 agbot_roleplay_character。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agbot_roleplay_character")
public class RoleplayCharacterEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色键，Redis 会话存这个，如 lvcha */
    private String roleKey;

    private String name;

    /** 额外触发词，逗号分隔 */
    private String triggers;

    private String instruction;

    private String ack;

    /** active / disabled */
    private String status;

    private Integer sortNo;
}
