package com.example.line.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在 HTTP 入口建立独立 trace_id 并返回响应头，与事件作用域和异步装饰器协作关联收发与落库。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    /**
     * 建立请求链路，成功、校验失败与异常结束均清理请求 MDC。
     * @param request 当前 HTTP 请求，不读取或记录凭证头/原始正文
     * @param response 添加服务端 X-Trace-Id 的响应
     * @param chain 后续过滤器与控制器
     * @throws IOException 下游 IO 失败时保持原异常传播
     * @throws ServletException 下游 Servlet 处理失败时保持原异常传播
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (var scope = LogContext.request()) {
            response.setHeader("X-Trace-Id", MDC.get("trace_id"));
            long start = System.nanoTime();
            boolean failed = false;
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException failure) {
                failed = true;
                BusinessLog.failure(log, Level.ERROR, LogEvents.HTTP_REQUEST_FAILED, "HTTP request failed", failure,
                        BusinessLog.fields("outcome", "FAILED", "http_status", 500));
                throw failure;
            } finally {
                BusinessLog.write(log, Level.INFO, LogEvents.HTTP_REQUEST_COMPLETED, "HTTP request completed",
                        BusinessLog.fields("http_status", failed ? 500 : response.getStatus(), "outcome",
                                !failed && response.getStatus() < 400 ? "SUCCESS" : "FAILED", "duration_ms",
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            }
        }
    }
}
