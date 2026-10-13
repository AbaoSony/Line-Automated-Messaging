package com.example.line.service;

import com.example.line.config.LineSendProperties;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LineApiCaller {
    private static final Logger log = LoggerFactory.getLogger(LineApiCaller.class);
    private final LineSendProperties properties;
    private final Sleeper sleeper;
    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @Autowired
    public LineApiCaller(LineSendProperties properties) { this(properties, Thread::sleep); }
    LineApiCaller(LineSendProperties properties, Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;
    }

    /** True only for Push 409 carrying LINE's accepted-request-id header. */
    public boolean execute(String operation, UUID pushRetryKey, Supplier<? extends CompletableFuture<?>> request) {
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
                if (e instanceof TimeoutException && future != null) future.cancel(true);
                Throwable failure = unwrap(e);
                Integer status = failure instanceof AbstractLineClientException api ? api.getCode() : null;
                if (pushRetryKey != null && status != null && status == 409
                        && failure instanceof AbstractLineClientException api
                        && api.getHeader("x-line-accepted-request-id") != null
                        && !api.getHeader("x-line-accepted-request-id").isBlank()) return true;
                boolean transientError = status != null ? status >= 500 && status <= 599
                        : failure instanceof IOException || failure instanceof TimeoutException;
                var reason = status != null && status >= 400 && status < 500
                        ? LineSendException.Reason.CLIENT_ERROR
                        : transientError ? LineSendException.Reason.TRANSIENT_FAILURE
                        : LineSendException.Reason.UNEXPECTED_FAILURE;
                boolean retry = transientError && attempt < attempts;
                log.warn("LINE send failed operation={}, status={}, attempt={}, retry={}",
                        operation, status, attempt, retry);
                if (!retry) throw new LineSendException(reason, status);
                try {
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
