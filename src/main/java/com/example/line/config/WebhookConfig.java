package com.example.line.config;

import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {
    private static final Logger log = LoggerFactory.getLogger(WebhookConfig.class);

    @Bean
    public ThreadPoolTaskExecutor webhookExecutor(WebhookProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("line-webhook-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((task, pool) -> {
            log.error("LINE webhook task rejected: active={}, queued={}, shutdown={}; request will be acknowledged but not processed",
                    pool.getActiveCount(), pool.getQueue().size(), pool.isShutdown());
            throw new RejectedExecutionException("LINE webhook executor unavailable");
        });
        return executor;
    }
}
