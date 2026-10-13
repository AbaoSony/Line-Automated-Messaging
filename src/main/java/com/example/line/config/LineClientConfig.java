package com.example.line.config;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LineProperties.class, InternalPushProperties.class})
public class LineClientConfig {
    @Bean
    public MessagingApiBlobClient messagingApiBlobClient(LineProperties properties) {
        return MessagingApiBlobClient.builder(properties.getChannelToken()).usingDefaultLogger(false).build();
    }

    @Bean
    public MessagingApiClient messagingApiClient(LineProperties properties) {
        return MessagingApiClient.builder(properties.getChannelToken()).usingDefaultLogger(false).build();
    }
}
