package com.example.line.common.persistence;

import com.example.line.common.logging.LogEvents;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.BusinessLog;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 隔离异步落库与队列拒绝的失败，供 chat/user 调用；不依赖业务包或持有事件 Token。 */
@Component
public class AsyncPersistence {
    private static final Logger log = LoggerFactory.getLogger(AsyncPersistence.class);
    private final Executor executor;
    private final MeterRegistry meters;

    /**
     * 注入有界持久化执行器与指标表。
     * @param executor 专用落库线程池
     * @param meters 失败计数注册表
     */
    public AsyncPersistence(@Qualifier("persistenceExecutor") Executor executor, MeterRegistry meters) {
        this.executor = executor;
        this.meters = meters;
    }

    /**
     * 提交仅包含允许业务数据的数据库操作；拒绝与落库异常均不传播到回复流程。
     * @param operation 已登记的业务事件名
     * @param userId 完整用户标识，不存在时允许 null
     * @param action 不包含 Token 或原始 Webhook 的落库操作
     */
    public void submit(String operation, String userId, Runnable action) {
        try {
            executor.execute(() -> {
                try (var scope = LogContext.user(userId)) {
                    action.run();
                } catch (RuntimeException failure) {
                    failed(operation, userId, failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            meters.counter("persistence.rejected").increment();
            try (var scope = LogContext.user(userId)) {
                BusinessLog.failure(log, Level.WARN, LogEvents.EXECUTOR_REJECTED, "Persistence task rejected", failure,
                        BusinessLog.fields("operation", operation, "outcome", "SKIPPED"));
                failed(operation, userId, failure);
            }
        }
    }

    private void failed(String operation, String userId, RuntimeException failure) {
        meters.counter("persist.failed", "operation", operation).increment();
        // JDBC 异常可能包含连接信息；只记录类型与调用帧，禁止输出异常消息和绑定参数。
        try (var scope = LogContext.user(userId)) {
            BusinessLog.failure(log, Level.ERROR, LogEvents.PERSIST_FAILED, "Database persistence failed", failure,
                    BusinessLog.fields("operation", operation, "outcome", "FAILED"));
        }
    }
}
