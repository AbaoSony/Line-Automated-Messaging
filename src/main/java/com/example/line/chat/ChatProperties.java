package com.example.line.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 保存聊天保留策略，清理开关默认关闭，由 ChatRetentionTask 读取。 */
@ConfigurationProperties("line.chat")
record ChatProperties(@DefaultValue("false") boolean cleanupEnabled,
                      @DefaultValue("90") int retentionDays,
                      @DefaultValue("100") int historyMaxLimit) {
    ChatProperties {
        if (retentionDays < 1 || historyMaxLimit < 1) {
            throw new IllegalArgumentException("line.chat retention-days and history-max-limit must be positive");
        }
    }
}
