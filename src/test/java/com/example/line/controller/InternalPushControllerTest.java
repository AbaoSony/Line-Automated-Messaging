package com.example.line.controller;

import com.example.line.config.InternalPushProperties;
import com.example.line.service.LineMessageService;
import com.example.line.service.LineSendException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalPushControllerTest {
    private final LineMessageService messages = mock(LineMessageService.class);
    private static final String BODY = "{\"userId\":\"Uaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"text\":\"hello\"}";
    private MockMvc mvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new InternalPushController(
                new InternalPushProperties(enabled, "test-internal-token"), messages))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
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
