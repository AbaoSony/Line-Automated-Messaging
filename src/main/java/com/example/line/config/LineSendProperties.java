package com.example.line.config;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定消息发送策略，供 LineApiCaller 限制 Push 重试和每次 SDK 等待时间。
 * 来源为 application.yml 的默认值或外部 Spring 配置，没有专用环境变量占位符；
 * 启动配置必须提供有效值，仓库默认值使用户无需额外填写。
 *
 * @param maxAttempts Push 总尝试次数，含首次，允许 1..5，默认 3；Reply 始终仅尝试一次
 * @param attemptTimeout 每次 Future 等待上限，必须大于零且不超过 5 秒，默认 3 秒
 * @param initialBackoff 第一次重试前的等待，必须大于零且不超过 1 秒，默认 200 毫秒
 */
@ConfigurationProperties("line.send")
public record LineSendProperties(int maxAttempts, Duration attemptTimeout, Duration initialBackoff) {
    /**
     * 限制尝试次数和等待范围，避免故障期间长时间占用调用线程。
     *
     * @param maxAttempts 含首次的 Push 总尝试次数，必须为 1..5
     * @param attemptTimeout 必填的单次等待上限，必须大于零且不超过 5 秒
     * @param initialBackoff 必填的初始退避，必须大于零且不超过 1 秒
     * @throws IllegalArgumentException 次数越界、时间缺失或不在允许范围时抛出
     */
    public LineSendProperties {
        if (maxAttempts < 1 || maxAttempts > 5 || attemptTimeout == null
                || attemptTimeout.isZero() || attemptTimeout.isNegative()
                || attemptTimeout.compareTo(Duration.ofSeconds(5)) > 0
                || initialBackoff == null || initialBackoff.isNegative()
                || initialBackoff.isZero() || initialBackoff.compareTo(Duration.ofSeconds(1)) > 0) {
            throw new IllegalArgumentException("line.send requires max-attempts 1..5, attempt-timeout (0,5s], initial-backoff (0,1s]");
        }
    }
}
