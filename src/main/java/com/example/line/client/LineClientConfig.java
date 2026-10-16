package com.example.line.client;

import com.example.line.messaging.internal.InternalPushProperties;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在应用启动阶段注册 LINE 凭证和内部调试配置，并向发送、下载服务提供官方 SDK 客户端。
 * 与 {@link LineProperties}、{@link InternalPushProperties} 协作校验配置；
 * 客户端关闭默认报文日志，防止 SDK 输出鉴权头、Reply Token 或消息正文。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LineProperties.class, InternalPushProperties.class})
public class LineClientConfig {
    /**
     * 创建访问 LINE 内容域名的客户端，供 LineContentService 按 messageId 下载内容。
     *
     * @param properties 已校验的必填凭证配置，Access Token 来源为 LINE_CHANNEL_TOKEN 或外部配置
     * @return 关闭默认 HTTP 报文日志的 Blob 客户端；创建时不调用 LINE API
     */
    @Bean
    public MessagingApiBlobClient messagingApiBlobClient(LineProperties properties) {
        return MessagingApiBlobClient.builder(properties.getChannelToken()).usingDefaultLogger(false).build();
    }

    /**
     * 创建消息客户端，供 LineMessageService 执行 Reply 与 Push。
     *
     * @param lineMessagingTransport 已关闭隐式重放并跟踪清理的官方 SDK 传输
     * @return 关闭默认 HTTP 报文日志的 Messaging API 客户端；创建时不验证远端凭证
     */
    @Bean
    public MessagingApiClient messagingApiClient(LineMessagingTransport lineMessagingTransport) {
        return lineMessagingTransport.client();
    }
    /**
     * 创建由容器负责关闭的消息传输，固定生产域名并关闭底层重放及报文日志。
     * @param properties 外部 LINE 凭证
     * @return 官方 SDK 接口的受控传输
     */
    @Bean(destroyMethod="close")
    public LineMessagingTransport lineMessagingTransport(LineProperties properties) {
        return new LineMessagingTransport(properties.getChannelToken(),java.net.URI.create("https://api.line.me"));
    }
}
