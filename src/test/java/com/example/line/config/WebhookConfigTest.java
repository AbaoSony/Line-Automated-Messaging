package com.example.line.config;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;

import static org.assertj.core.api.Assertions.*;

/** 验证有界线程池饱和后记录拒绝日志且不会在请求线程回退执行业务。 */
@ExtendWith(OutputCaptureExtension.class)
class WebhookConfigTest {
    // 场景：一个任务执行、一个任务排队后，新任务被拒绝并输出队列水位。
    @Test
    void fullBoundedPoolRejectsWithLogInsteadOfRunningOnCaller(CapturedOutput output) throws Exception {
        var executor = new WebhookConfig().webhookExecutor(
                new WebhookProperties(1, 1, Duration.ofHours(24), 100, 100));
        executor.initialize();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {});
            assertThatThrownBy(() -> executor.execute(() -> fail("Must not run on request thread")))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(output).contains("LINE webhook task rejected", "queued=1");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
