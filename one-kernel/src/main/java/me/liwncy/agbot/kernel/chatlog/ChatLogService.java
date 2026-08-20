package me.liwncy.agbot.kernel.chatlog;

import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;

/**
 * 通道聊天记录。写入失败不得打断收发。
 */
public interface ChatLogService {

    void recordInbound(MsgInfo msgInfo);

    void recordOutbound(String adapterId, ReplyInfo replyInfo, String outboundMsgId, String replyStatus);
}
