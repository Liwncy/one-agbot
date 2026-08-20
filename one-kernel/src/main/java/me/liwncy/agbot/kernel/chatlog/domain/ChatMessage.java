package me.liwncy.agbot.kernel.chatlog.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import me.liwncy.agbot.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 通道聊天记录 agbot_chat_message。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agbot_chat_message")
public class ChatMessage extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String platform;

    private String adapter;

    private String accountId;

    private String messageId;

    private String sessionId;

    private String sessionType;

    private String direction;

    private String senderId;

    private String senderName;

    private String msgType;

    private String contentText;

    private String adapterExtra;

    private String referMessageId;

    private String causedByMessageId;

    private Integer replyIndex;

    private String replyStatus;

    private LocalDateTime msgTime;
}
