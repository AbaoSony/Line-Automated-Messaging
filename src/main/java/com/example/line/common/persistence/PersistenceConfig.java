package com.example.line.common.persistence;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 提供独立有界落库执行器，防止数据库等待占用 Webhook 回复线程。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceProperties.class)
class PersistenceConfig {
    @Bean
    ThreadPoolTaskExecutor persistenceExecutor(PersistenceProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("line-persist-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(properties.shutdownTimeout().toMillis());
        executor.setTaskDecorator(task -> {
            Map<String, String> caller = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (caller == null) MDC.clear(); else MDC.setContextMap(caller);
                    task.run();
                } finally {
                    if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
                }
            };
        });
        // 不使用 CallerRunsPolicy，数据库故障和积压不得拖慢即时回复。
        return executor;
    }
}
