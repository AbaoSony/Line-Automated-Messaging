package com.example.line.common.logging.recording;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 配置事件表独立保留策略，默认关闭自动删除。 */
@ConfigurationProperties("line.event-log")
public record EventRetentionProperties(@DefaultValue("false") boolean cleanupEnabled,
                                       @DefaultValue("30") int retentionDays) {
    /**
     * 校验保留天数，不因开关关闭而接受无效配置。
     * @param cleanupEnabled 是否显式启用事件清理
     * @param retentionDays 事件保留天数
     * @throws IllegalArgumentException 保留天数小于一天时抛出
     */
    public EventRetentionProperties {
        if (retentionDays < 1) throw new IllegalArgumentException("line.event-log.retention-days must be positive");
    }
}
