package com.example.line.common.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 提供日志固定身份字段，由启动期格式器和 LoggingConfig 共同绑定校验。 */
@ConfigurationProperties("line.logging")
public record LoggingProperties(@DefaultValue("line-messaging-service") String service,
                                @DefaultValue("local") String env,
                                @DefaultValue("0.0.1-SNAPSHOT") String version,
                                @DefaultValue("local") String instance,
                                @DefaultValue("logs/application-readable.log") String readableFile) {
    /**
     * 校验日志身份配置，不在错误中输出配置值。
     * @param service 服务名称
     * @param env 部署环境名称
     * @param version 应用版本
     * @param instance 实例名称
     * @param readableFile local profile 的额外易读文件路径
     * @throws IllegalArgumentException 任一字段为空白时抛出
     */
    public LoggingProperties {
        if (service == null || service.isBlank() || env == null || env.isBlank()
                || version == null || version.isBlank() || instance == null || instance.isBlank()
                || readableFile == null || readableFile.isBlank()) {
            throw new IllegalArgumentException("line.logging identity fields and readable-file must be non-blank");
        }
    }
}
