package com.example.line.common.persistence;

import com.example.line.common.logging.MdcTaskDecorator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 提供独立有界落库执行器，防止数据库等待占用 Webhook 回复线程。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceProperties.class)
class PersistenceConfig {
    @Bean
    ThreadPoolTaskExecutor persistenceExecutor(PersistenceProperties properties, MeterRegistry meters) {
        var executor = new DrainingTaskExecutor();
        executor.setPhase(DrainingTaskExecutor.PERSISTENCE_PHASE);
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("line-persist-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(properties.shutdownTimeout().toMillis());
        executor.setTaskDecorator(new MdcTaskDecorator());
        meters.gauge("persistence.queue.size", executor, ThreadPoolTaskExecutor::getQueueSize);
        // 不使用 CallerRunsPolicy，数据库故障和积压不得拖慢即时回复。
        return executor;
    }
}
