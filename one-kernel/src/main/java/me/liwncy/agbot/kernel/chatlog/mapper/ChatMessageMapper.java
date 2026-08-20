package me.liwncy.agbot.kernel.chatlog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
