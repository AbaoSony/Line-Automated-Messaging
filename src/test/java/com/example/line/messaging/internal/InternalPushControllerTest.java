package com.example.line.messaging.internal;

import com.example.line.common.exception.ApiExceptionHandler;

import com.example.line.messaging.internal.InternalPushProperties;
import com.example.line.messaging.LineMessageService;
import com.example.line.messaging.LineSendException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证内部 Push 的开关、独立鉴权、接受回执和安全错误映射。 */
class InternalPushControllerTest {
    private final LineMessageService messages = mock(LineMessageService.class);
    private static final String BODY = "{\"userId\":\"Uaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"text\":\"hello\"}";
    private MockMvc mvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new InternalPushController(
                new InternalPushProperties(enabled, "test-internal-token"), messages))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    // 场景：关闭接口或鉴权缺失/错误时，不调用消息发送服务。
    @Test
    void disabledReturns404AndUnauthorizedReturns401WithoutSending() throws Exception {
        mvc(false).perform(post("/internal/push").contentType("application/json").content(BODY))
                .andExpect(status().isNotFound());
        var mvc = mvc(true);
        mvc.perform(post("/internal/push").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/push").contentType("application/json").content(BODY)
                .header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(messages);
    }
    // 场景：正确鉴权的请求返回 ACCEPTED 和本次 Retry Key。
    @Test
    void authorizedPushReturnsAcceptanceReceipt() throws Exception {
        UUID key = UUID.randomUUID();
        when(messages.pushText(any(), any())).thenReturn(new LineMessageService.PushReceipt(key, false));
        mvc(true).perform(post("/internal/push").contentType("application/json").content(BODY)
                .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.retryKey").value(key.toString()));
        verify(messages).pushText("Uaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "hello");
    }
    // 场景：参数、上游 4xx 与瞬时故障映射到不同 HTTP 状态，且不泄露原始错误消息。
    @Test
    void sanitizedErrorsDistinguishValidationClientAndTransientFailures() throws Exception {
        when(messages.pushText(any(), any()))
                .thenThrow(new IllegalArgumentException("private-body"))
                .thenThrow(new LineSendException(LineSendException.Reason.CLIENT_ERROR, 401))
                .thenThrow(new LineSendException(LineSendException.Reason.TRANSIENT_FAILURE, 503));
        var mvc = mvc(true);
        mvc.perform(post("/internal/push").contentType("application/json").content(BODY)
                .header("X-Internal-Token", "test-internal-token")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/internal/push").contentType("application/json").content(BODY)
                .header("X-Internal-Token", "test-internal-token")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.upstreamStatus").value(401));
        mvc.perform(post("/internal/push").contentType("application/json").content(BODY)
                .header("X-Internal-Token", "test-internal-token")).andExpect(status().isServiceUnavailable());
    }
}
