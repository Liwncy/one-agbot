package me.liwncy.agbot.adapter.golem.api;

import me.liwncy.agbot.adapter.golem.GolemProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemApiClientConfiguration {

    @Bean
    public GolemApiClient golemApiClient(GolemProperties properties) {
        return new GolemApiClient(properties);
    }
}
