package com.example.line.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 限制单实例 AI 会话的统一时限、资源和提示，所有提示通过 LINE 文本长度校验。 */
@ConfigurationProperties("line.ai")
public record AiProperties(@DefaultValue("45s") Duration totalTimeout,
                           @DefaultValue("3s") Duration sendReserve,
                           @DefaultValue("3s") Duration queueWait,
                           @DefaultValue("500ms") Duration lockWait,
                           @DefaultValue("500ms") Duration dependencyTimeout,
                           @DefaultValue("4") int workers,
                           @DefaultValue("256") int queueCapacity,
                           @DefaultValue("3") int userQueueCapacity,
                           @DefaultValue("1024") int maxUsers,
                           @DefaultValue("剛剛沒能順利回覆，可以再說一次嗎？") String fallback,
                           @DefaultValue("我還在處理上一則訊息，稍等一下再傳喔。") String busy,
                           @DefaultValue("訊息有點多，稍等一下再聊喔。") String rateLimited,
                           @DefaultValue("服務暫時開小差了，晚點再試一下喔。") String unavailable,
                           @DefaultValue("這則訊息有點長，可以分短一點說嗎？") String inputTooLong) {
    // 保留 LINE 短效 Reply 的网络余量，不能通过配置变成分钟级延迟任务。
    private static final Duration MAX_TOTAL_TIMEOUT=Duration.ofSeconds(50);
    /**
     * 校验资源、时限与配置提示，避免首次失败时才发现提示无法发送。
     * @throws IllegalArgumentException 配置不满足处理与发送边界时抛出
     */
    public AiProperties {
        for (Duration duration : new Duration[]{totalTimeout,sendReserve,queueWait,lockWait,dependencyTimeout})
            if (duration == null || duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("Invalid AI timeout");
        if (totalTimeout.compareTo(MAX_TOTAL_TIMEOUT) > 0 || totalTimeout.compareTo(sendReserve) <= 0
                || workers < 1 || queueCapacity < 1 || userQueueCapacity < 1 || maxUsers < 1)
            throw new IllegalArgumentException("Invalid AI resource budget; total-timeout must exceed send-reserve and not exceed 50s");
        for (String text : new String[]{fallback,busy,rateLimited,unavailable,inputTooLong})
            com.example.line.messaging.LineMessages.text(text);
    }
}
