package com.example.line.common.logging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 将日志身份配置纳入应用启动校验，格式器同时支持 Spring 容器创建前的日志初始化。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoggingProperties.class)
class LoggingConfig {}
