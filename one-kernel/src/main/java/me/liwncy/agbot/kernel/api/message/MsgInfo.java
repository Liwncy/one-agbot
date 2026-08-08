package me.liwncy.agbot.kernel.api.message;

import java.util.Collections;
import java.util.Map;

/**
 * 统一入站消息（对齐 Bncr receive 字段）。
 */
public record MsgInfo(
        String platform,
        String accountId,
        String userId,
        String userName,
        String groupId,
        String groupName,
        String msg,
        String msgId,
        String fromType,
        String msgType,
        String path,
        String replyToMsgId,
        Long createTime,
        Map<String, Object> extra
) {
    public MsgInfo {
        if (accountId == null || accountId.isBlank()) {
            accountId = "default";
        }
        if (groupId == null || groupId.isBlank()) {
            groupId = "0";
        }
        if (fromType == null || fromType.isBlank()) {
            fromType = "Social";
        }
        if (msgType == null || msgType.isBlank()) {
            msgType = MsgType.TEXT;
        }
        if (extra == null) {
            extra = Collections.emptyMap();
        }
        if (createTime == null) {
            createTime = System.currentTimeMillis();
        }
    }

    public boolean isPrivateChat() {
        return "0".equals(groupId);
    }
}
