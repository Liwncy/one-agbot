package me.liwncy.agbot.common.mybatis.config;

import cn.hutool.core.net.NetUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import me.liwncy.agbot.common.mybatis.handler.InjectionMetaObjectHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.net.InetAddress;

/**
 * MyBatis-Plus：雪花 ID、审计字段填充。
 */
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class MybatisPlusConfig {

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new InjectionMetaObjectHandler();
    }

    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    public IdentifierGenerator idGenerator() {
        InetAddress localhost = NetUtil.getLocalhost();
        return localhost == null ? new DefaultIdentifierGenerator() : new DefaultIdentifierGenerator(localhost);
    }
}
