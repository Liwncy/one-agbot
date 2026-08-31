package me.liwncy.agbot.kernel.api.agent;

import me.liwncy.agbot.kernel.api.message.MsgInfo;

/**
 * 角色口令。命中则宿主处理并返回回复，不进模型。
 * {@code commandText} 可以是已去掉点名前缀的正文。
 */
public interface RoleplayCommands {

    /**
     * @return 已处理时的回复；不是口令则 {@code null}
     */
    String tryHandle(MsgInfo msg, String commandText);
}
