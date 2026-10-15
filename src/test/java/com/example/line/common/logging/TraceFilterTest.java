package com.example.line.common.logging;

import jakarta.servlet.ServletException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

/** 验证 HTTP 链路由服务端生成，成功、错误与线程复用时均不会残留上一次请求身份。 */
class TraceFilterTest {
    @AfterEach
    void clearContext() { MDC.clear(); }

    @Test
    void serverGeneratesTraceIdIgnoresClientHeaderAndClearsRequestContext() throws Exception {
        var request = new MockHttpServletRequest("POST", "/line/webhook");
        request.addHeader("X-Trace-Id", "client-supplied-fake-token");
        var response = new MockHttpServletResponse();
        var captured = new AtomicReference<String>();
        new TraceFilter().doFilter(request, response, (incoming, outgoing) -> {
            captured.set(MDC.get("trace_id"));
            MDC.put("user_id", "Ufictional-complete-user");
        });
        assertThat(UUID.fromString(response.getHeader("X-Trace-Id"))).isNotNull();
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(captured.get()).isNotEqualTo("client-supplied-fake-token");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void exceptionPropagatesAndRestoresOriginalContext() {
        MDC.setContextMap(Map.of("outer", "preserved"));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new TraceFilter().doFilter(new MockHttpServletRequest("POST", "/line/webhook"),
                response, (incoming, outgoing) -> { throw new ServletException("fake-private-credential"); }))
                .isInstanceOf(ServletException.class);
        assertThat(UUID.fromString(response.getHeader("X-Trace-Id"))).isNotNull();
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("outer", "preserved"));
    }

    @Test
    void consecutiveRequestsHaveDifferentTracesAndDoNotShareUserContext() throws Exception {
        var filter = new TraceFilter();
        var first = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/line/webhook"), first,
                (request, response) -> MDC.put("user_id", "Ufictional-first-user"));
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/line/webhook"), second,
                (request, response) -> assertThat(MDC.get("user_id")).isNull());
        assertThat(first.getHeader("X-Trace-Id")).isNotEqualTo(second.getHeader("X-Trace-Id"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
