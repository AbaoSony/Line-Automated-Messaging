package com.example.line.config;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("line.send")
public record LineSendProperties(int maxAttempts, Duration attemptTimeout, Duration initialBackoff) {
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
