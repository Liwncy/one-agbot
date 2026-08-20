package me.liwncy.agbot.kernel.chatlog;

import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;

import java.util.List;

/**
 * 通道聊天记录。写入失败不得打断收发。
 */
public interface ChatLogService {

    void recordInbound(MsgInfo msgInfo);

    void recordOutbound(String adapterId, ReplyInfo replyInfo, String outboundMsgId, String replyStatus);

    /**
     * 按会话取最近消息，返回时间正序（旧 → 新）。
     */
    List<ChatMessage> listRecent(ChatLogQuery query);

    /**
     * 按通道消息 id 查找（同一 account 下可能跨 platform 多条）。
     */
    List<ChatMessage> listByMessageId(String accountId, String messageId);
}
