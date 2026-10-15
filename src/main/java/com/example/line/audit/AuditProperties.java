package com.example.line.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 配置审计独立保留策略，默认关闭，不跟随聊天或事件清理开关。 */
@ConfigurationProperties("line.audit-log")
record AuditProperties(@DefaultValue("false") boolean cleanupEnabled, @DefaultValue("180") int retentionDays) {
    AuditProperties {
        if (retentionDays < 1) throw new IllegalArgumentException("line.audit-log.retention-days must be positive");
    }
}
