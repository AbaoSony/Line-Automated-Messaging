package com.example.line.webhook;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogEvents;
import com.example.line.common.logging.MdcTaskDecorator;
import java.util.concurrent.RejectedExecutionException;
import io.micrometer.core.instrument.MeterRegistry;
import com.example.line.common.persistence.DrainingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 装配 Webhook 专用有界线程池，连接快速确认请求的 WebhookService 与耗时 Handler。
 * 与 WebhookProperties 协作限制并发和排队，拒绝时记录资源水位，不回退到请求线程执行。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {
    private static final Logger log = LoggerFactory.getLogger(WebhookConfig.class);

    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor webhookAdmissionExecutor(WebhookProperties properties,MeterRegistry meters) {
        var executor=new com.example.line.common.persistence.DrainingTaskExecutor();
        executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("line-webhook-admission-");executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setPhase(com.example.line.common.persistence.DrainingTaskExecutor.WEBHOOK_PHASE+1);
        executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(30);
        meters.gauge("webhook.admission.queue.size",executor,ThreadPoolTaskExecutor::getQueueSize);
        executor.setRejectedExecutionHandler((task,pool) -> {
            meters.counter("webhook.rejected").increment();
            throw new RejectedExecutionException("LINE webhook admission unavailable");
        });
        return executor;
    }

    /**
     * 创建固定并发、有界队列的业务执行器，阻止慢业务拖住 HTTP 响应线程。
     *
     * @param properties 有效的资源配置，默认 4 个工作线程和 256 个排队请求
     * @param meters 拒绝指标注册表，每次拒绝只在执行器边界计数一次
     * @return 由 Spring 管理生命周期的执行器；任务拒绝会记录日志并抛出拒绝异常
     */
    @Bean
    public ThreadPoolTaskExecutor webhookExecutor(WebhookProperties properties, MeterRegistry meters) {
        var executor = new DrainingTaskExecutor();
        executor.setPhase(DrainingTaskExecutor.WEBHOOK_PHASE);
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("line-webhook-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        meters.gauge("webhook.queue.size", executor, ThreadPoolTaskExecutor::getQueueSize);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 最多等待 30 秒是本项目的停机预算，并不承诺积压队列一定排空。
        executor.setAwaitTerminationSeconds(30);
        // 保持“快速返回 200”的现有策略；拒绝任务会丢失，不能将该日志视为可靠入队。
        executor.setRejectedExecutionHandler((task, pool) -> {
            meters.counter("webhook.rejected").increment();
            BusinessLog.write(log, Level.ERROR, LogEvents.EXECUTOR_REJECTED,
                    "LINE webhook task rejected: active={}, queued={}, shutdown={}; request will be acknowledged but not processed",
                    BusinessLog.fields("active", pool.getActiveCount(), "queued", pool.getQueue().size(),
                            "shutdown", pool.isShutdown(), "outcome", "SKIPPED"),
                    pool.getActiveCount(), pool.getQueue().size(), pool.isShutdown());
            throw new RejectedExecutionException("LINE webhook executor unavailable");
        });
        return executor;
    }
}
