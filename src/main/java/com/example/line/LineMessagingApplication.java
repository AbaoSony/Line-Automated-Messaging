package com.example.line;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 应用入口，启动 HTTP 接入、LINE 客户端配置和异步事件处理组件。
 * 通过包扫描装配 config/controller/handler/service，各层职责由对应组件承担。
 */
@SpringBootApplication
public class LineMessagingApplication {
    /**
     * 启动服务，并在启动配置无效时由 Spring 快速失败。
     *
     * @param args Spring Boot 命令行参数；真实凭证应从环境或受保护外部配置注入，避免进入命令历史
     */
    public static void main(String[] args) {
        SpringApplication.run(LineMessagingApplication.class, args);
    }
}
