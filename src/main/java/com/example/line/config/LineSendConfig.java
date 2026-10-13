package com.example.line.config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 在 Spring 启动时注册 LineSendProperties，向 LineApiCaller 提供有限重试与等待配置；
 * 与 LINE 客户端装配分离，使发送策略配置具有独立的校验入口。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LineSendProperties.class)
public class LineSendConfig {}
