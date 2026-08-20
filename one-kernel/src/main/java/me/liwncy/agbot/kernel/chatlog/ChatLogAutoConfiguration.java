package me.liwncy.agbot.kernel.chatlog;

import me.liwncy.agbot.kernel.chatlog.mapper.ChatMessageMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration
@AutoConfigureAfter(name = "me.liwncy.agbot.common.mybatis.config.MybatisPlusConfig")
@ConditionalOnBean(DataSource.class)
@MapperScan("me.liwncy.agbot.kernel.chatlog.mapper")
public class ChatLogAutoConfiguration {

    @Bean
    public ChatLogService chatLogService(ChatMessageMapper mapper) {
        return new ChatLogServiceImpl(mapper);
    }
}
