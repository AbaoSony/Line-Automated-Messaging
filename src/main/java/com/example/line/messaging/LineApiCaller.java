package com.example.line.messaging;

import com.example.line.messaging.LineSendProperties;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 统一执行 SDK Future、分类上游错误并限制 Push 重试，供 LineMessageService 使用。
 * 与 LineSendProperties、LineSendException 协作；Reply 仅调用一次，
 * Push 由调用方固定 Retry Key 和请求体，本层仅对 5xx/网络/超时进行有限退避。
 */
@Service
public class LineApiCaller {
    private static final Logger log = LoggerFactory.getLogger(LineApiCaller.class);
    private final LineSendProperties properties;
    private final Sleeper sleeper;
    /** 将退避等待与重试判断分离，测试可观察等待序列而不实际阻塞。 */
    @FunctionalInterface
    interface Sleeper {
        /**
         * 在重试前执行等待。
         *
         * @param millis 由初始退避乘以二的幂得到的非负毫秒数
         * @throws InterruptedException 等待被中断时抛出，由调用器恢复线程中断标记
         */
        void sleep(long millis) throws InterruptedException;
    }
    /**
     * 使用生产配置和线程等待构造 SDK 调用器。
     *
     * @param properties 已校验策略，默认 Push 总尝试 3 次、单次等待 3 秒、首次退避 200 毫秒
     */
    @Autowired
    public LineApiCaller(LineSendProperties properties) { this(properties, Thread::sleep); }
    // 测试注入可观测的等待函数，以验证指数退避、有限次数与中断行为。
    LineApiCaller(LineSendProperties properties, Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;
    }

    /**
     * True only for Push 409 carrying LINE's accepted-request-id header.
     * 执行一次 Reply 或带稳定 Key 的 Push，成功停止；4xx 不重试，瞬时错误仅对 Push 重试。
     *
     * @param operation 安全日志中的操作名，调用方应使用 reply/push 等固定值，不携带凭证或正文
     * @param pushRetryKey Push 本次调用的 UUID；null 表示不支持 Retry Key 的 Reply，仅尝试一次
     * @param request 每次创建 SDK Future 的调用函数；Push 必须复用相同 Key、收件人和消息体
     * @return 正常 SDK 成功为 false；409 且带非空 accepted-request-id 时为 true，均只表示接受状态
     * @throws LineSendException 4xx、有限重试耗尽、未预期 SDK 错误或中断时抛出安全分类异常
     */
    public boolean execute(String operation, UUID pushRetryKey, Supplier<? extends CompletableFuture<?>> request) {
        // Reply Token 一次性且短效，不能照搬 Push 的幂等重试策略。
        int attempts = pushRetryKey == null ? 1 : properties.maxAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            CompletableFuture<?> future = null;
            try {
                future = request.get();
                future.get(properties.attemptTimeout().toMillis(), TimeUnit.MILLISECONDS);
                return false;
            } catch (InterruptedException e) {
                if (future != null) future.cancel(true);
                Thread.currentThread().interrupt();
                throw new LineSendException(LineSendException.Reason.INTERRUPTED, null);
            } catch (Exception e) {
                // 取消 Future 只减少本地等待，不证明 LINE 未执行；Push 后续仍须使用相同 Key。
                if (e instanceof TimeoutException && future != null) future.cancel(true);
                Throwable failure = unwrap(e);
                Integer status = failure instanceof AbstractLineClientException api ? api.getCode() : null;
                // 普通 409 仍是客户端错误，只有 LINE 确认已有接受记录的冲突才当作成功。
                if (pushRetryKey != null && status != null && status == 409
                        && failure instanceof AbstractLineClientException api
                        && api.getHeader("x-line-accepted-request-id") != null
                        && !api.getHeader("x-line-accepted-request-id").isBlank()) return true;
                // SDK HTTP 异常也继承 IOException，必须先判断状态，避免将 4xx 错当网络故障。
                boolean transientError = status != null ? status >= 500 && status <= 599
                        : failure instanceof IOException || failure instanceof TimeoutException;
                var reason = status != null && status >= 400 && status < 500
                        ? LineSendException.Reason.CLIENT_ERROR
                        : transientError ? LineSendException.Reason.TRANSIENT_FAILURE
                        : LineSendException.Reason.UNEXPECTED_FAILURE;
                boolean retry = transientError && attempt < attempts;
                // 不输出原始异常消息或堆栈，SDK 错误可能包含请求正文或服务端回显内容。
                log.warn("LINE send failed operation={}, status={}, attempt={}, retry={}",
                        operation, status, attempt, retry);
                if (!retry) throw new LineSendException(reason, status);
                try {
                    // 默认首次失败等 200ms、再次失败等 400ms；二倍增长且次数有界，避免故障期紧密重试。
                    sleeper.sleep(properties.initialBackoff().toMillis() * (1L << (attempt - 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LineSendException(LineSendException.Reason.INTERRUPTED, status);
                }
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }
    private Throwable unwrap(Throwable exception) {
        while ((exception instanceof ExecutionException || exception instanceof CompletionException)
                && exception.getCause() != null) exception = exception.getCause();
        return exception;
    }
}
