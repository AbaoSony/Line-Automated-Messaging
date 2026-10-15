package com.example.line.chat;

import com.example.line.common.logging.LogEvents;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 使用完整虚构 HTTP 流程验证 trace 贯穿回复与落库失败、完整数据保留和凭证不进入任何业务日志。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "line.bot.channel-secret=fake-flow-channel-secret", "line.bot.channel-token=fake-flow-channel-token",
        "line.internal-push.enabled=true", "line.internal-push.token=fake-flow-internal-token",
        "line.send.initial-backoff=1ms"})
@ExtendWith(OutputCaptureExtension.class)
class LoggingFlowIntegrationTest extends PostgresIntegrationSupport {
    private static final String USER = "Udddddddddddddddddddddddddddddddd";
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired private TestRestTemplate http;
    @Autowired private ThreadPoolTaskExecutor webhookExecutor;
    @Autowired private ThreadPoolTaskExecutor webhookAdmissionExecutor;
    @Autowired private com.example.line.ai.coordination.SessionScheduler aiScheduler;
    @Autowired private ThreadPoolTaskExecutor persistenceExecutor;
    @MockitoBean private MessagingApiClient client;
    @MockitoSpyBean private ChatRepository repository;
    @MockitoSpyBean private com.example.line.common.logging.recording.EventLogRepository logRepository;
    @Autowired private com.example.line.common.logging.EventRecorder recorder;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void fullFlowRetainsOriginalDataSharesTraceAcrossWorkersAndNeverLogsCredentials(CapturedOutput logs) throws Exception {
        when(client.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new DataAccessResourceFailureException("fake-flow-database-password"))
                .when(repository).insert(any(), any(), nullable(String.class), any(), any(), any(),any());
        String body = """
                {"destination":"Ufictional-bot","events":[{"type":"message","webhookEventId":"fictional-flow-event",
                 "timestamp":1,"mode":"active","deliveryContext":{"isRedelivery":false},
                 "source":{"type":"user","userId":"%s"},"replyToken":"fake-flow-reply-token",
                 "message":{"type":"text","id":"fictional-flow-message","text":"完整虚构中文消息",
                 "quoteToken":"fake-flow-quote-token","markAsReadToken":"fake-flow-read-token"}}]}
                """.formatted(USER);
        var headers = signedHeaders(body);
        String signature = headers.getFirst("X-Line-Signature");
        headers.set("X-Trace-Id", "fake-client-provided-trace");
        var response = http.postForEntity("/line/webhook", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(UUID.fromString(traceId)).isNotNull();
        assertThat(traceId).isNotEqualTo("fake-client-provided-trace");
        drain();
        verify(client).replyMessage(argThat(request -> request.replyToken().equals("fake-flow-reply-token")));

        List<JsonNode> events = events(logs.getOut(), traceId);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.path("event").asText()).isEqualTo(LogEvents.USER_MESSAGE_RECEIVED);
            assertThat(event.path("user_id").asText()).isEqualTo(USER);
            assertThat(event.path("webhook_event_id").asText()).isEqualTo("fictional-flow-event");
            assertThat(event.path("content").path("text").asText()).isEqualTo("完整虚构中文消息");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.path("event").asText()).isEqualTo(LogEvents.LINE_REPLY_REQUESTED);
            assertThat(event.path("messages").get(0).path("text").asText()).isEqualTo("完整虚构中文消息");
        });
        assertThat(events).anySatisfy(event -> assertThat(event.path("event").asText()).isEqualTo(LogEvents.LINE_REPLY_SENT));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.path("event").asText()).isEqualTo(LogEvents.PERSIST_FAILED);
            assertThat(event.path("thread").asText()).startsWith("line-persist-");
            assertThat(event.path("user_id").asText()).isEqualTo(USER);
            assertThat(event.path("webhook_event_id").asText()).isEqualTo("fictional-flow-event");
        });
        for (var event : events) {
            for (String field : List.of("ts", "level", "service", "env", "version", "instance", "trace_id", "event")) {
                assertThat(event.path(field).asText()).isNotBlank();
            }
        }

        headers.set("X-Line-Signature", "invalid-fake-signature");
        var invalid = http.postForEntity("/line/webhook", new HttpEntity<>(body, headers), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(UUID.fromString(invalid.getHeaders().getFirst("X-Trace-Id"))).isNotNull();
        assertThat(events(logs.getOut(), invalid.getHeaders().getFirst("X-Trace-Id"))).anySatisfy(event ->
                assertThat(event.path("event").asText()).isEqualTo(LogEvents.WEBHOOK_SIGNATURE_INVALID));

        String malformed = "{\"replyToken\":\"fake-malformed-reply-token\", broken-json";
        assertThat(http.postForEntity("/line/webhook", new HttpEntity<>(malformed, signedHeaders(malformed)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        drain();

        var upstream = mock(AbstractLineClientException.class);
        when(upstream.getCode()).thenReturn(503);
        when(upstream.getMessage()).thenReturn("fake-upstream-api-key");
        when(client.pushMessage(any(), any())).thenReturn(CompletableFuture.failedFuture(upstream), CompletableFuture.completedFuture(null));
        var pushHeaders = new HttpHeaders();
        pushHeaders.setContentType(MediaType.APPLICATION_JSON);
        pushHeaders.set("X-Internal-Token", "fake-flow-internal-token");
        var push = http.postForEntity("/internal/push", new HttpEntity<>(
                mapper.writeValueAsString(java.util.Map.of("userId", USER, "text", "完整虚构推送内容")), pushHeaders), String.class);
        assertThat(push.getStatusCode()).isEqualTo(HttpStatus.OK);
        var pushEvents = events(logs.getOut(), push.getHeaders().getFirst("X-Trace-Id"));
        assertThat(pushEvents).anySatisfy(event -> assertThat(event.path("event").asText()).isEqualTo(LogEvents.LINE_RETRY));
        assertThat(pushEvents).anySatisfy(event -> {
            assertThat(event.path("event").asText()).isEqualTo(LogEvents.LINE_PUSH_REQUESTED);
            assertThat(event.path("messages").get(0).path("text").asText()).isEqualTo("完整虚构推送内容");
        });
        assertThat(pushEvents).anySatisfy(event -> assertThat(event.path("event").asText()).isEqualTo(LogEvents.LINE_PUSH_SENT));

        pushHeaders.set("X-Internal-Token", "fake-untrusted-internal-token");
        var unauthorized = http.postForEntity("/internal/push", new HttpEntity<>(
                mapper.writeValueAsString(java.util.Map.of("userId", USER, "text", "虚构未授权请求")), pushHeaders), String.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(UUID.fromString(unauthorized.getHeaders().getFirst("X-Trace-Id"))).isNotNull();
        verify(client, times(2)).pushMessage(any(), any());
        var notFound = http.getForEntity("/fictional-unknown-route", String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(UUID.fromString(notFound.getHeaders().getFirst("X-Trace-Id"))).isNotNull();

        assertThat(logs).doesNotContain("fake-flow-channel-secret", "fake-flow-channel-token", "fake-flow-internal-token",
                "fake-flow-reply-token", "fake-flow-quote-token", "fake-flow-read-token", "fake-flow-database-password",
                "fake-malformed-reply-token", "fake-upstream-api-key", "fake-client-provided-trace", "fake-untrusted-internal-token", signature);
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
        assertThat(jdbc.queryForObject("SELECT detail->'content'->>'text' FROM event_log WHERE trace_id = ? AND event = 'user.message.received'",
                String.class, traceId)).isEqualTo("完整虚构中文消息");
        String storedAudit = jdbc.queryForObject("SELECT detail::text FROM audit_log WHERE trace_id = ?", String.class,
                push.getHeaders().getFirst("X-Trace-Id"));
        assertThat(storedAudit).contains("完整虚构推送内容");
        String allData = jdbc.queryForList("SELECT detail::text FROM event_log", String.class).toString()
                + jdbc.queryForList("SELECT detail::text FROM audit_log", String.class);
        assertThat(allData).doesNotContain("fake-flow-channel-secret", "fake-flow-channel-token", "fake-flow-internal-token",
                "fake-flow-reply-token", "fake-flow-quote-token", "fake-flow-read-token", "fake-flow-database-password",
                "fake-malformed-reply-token", "fake-upstream-api-key", "fake-untrusted-internal-token", signature, "stack_trace");
    }

    @Test
    void databaseAuditFailureStillReturnsAcceptedAndFallsBackWithoutRecursion(CapturedOutput logs) throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
        doThrow(new DataAccessResourceFailureException("fake-private-log-database-password")).when(logRepository).write(anyList());
        when(client.pushMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", "fake-flow-internal-token");
        var response = http.postForEntity("/internal/push", new HttpEntity<>(mapper.writeValueAsString(
                java.util.Map.of("userId", USER, "text", "虚构审计失败仍成功推送")), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
        verify(client).pushMessage(any(), any());
        String trace = response.getHeaders().getFirst("X-Trace-Id");
        var matching = events(logs.getOut(), trace);
        assertThat(matching).anySatisfy(event -> {
            assertThat(event.path("event").asText()).isEqualTo("internal.push.invoked");
            assertThat(event.path("persistence_fallback").asBoolean()).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE trace_id = ?", Integer.class, trace)).isZero();
        assertThat(meters.get("persist.failed").tag("operation", "audit_log").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(logs).doesNotContain("fake-private-log-database-password", "fake-flow-internal-token");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE event = 'persist.failed'", Integer.class)).isZero();
    }

    @Test
    void finalSendFailureCountsOnceAndAuditsFailedOperation(CapturedOutput logs) throws Exception {
        var upstream = mock(AbstractLineClientException.class);
        when(upstream.getCode()).thenReturn(401);
        when(upstream.getMessage()).thenReturn("fake-private-upstream-key");
        when(client.pushMessage(any(), any())).thenReturn(CompletableFuture.failedFuture(upstream));
        when(client.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(upstream));
        double pushBefore = meters.get("line.push.failed").counter().count();
        double replyBefore = meters.get("line.reply.failed").counter().count();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", "fake-flow-internal-token");
        var response = http.postForEntity("/internal/push", new HttpEntity<>(mapper.writeValueAsString(
                java.util.Map.of("userId", USER, "text", "虚构失败推送")), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        String body = """
                {"events":[{"type":"message","webhookEventId":"fictional-failed-reply","timestamp":1,"mode":"active",
                "source":{"type":"user","userId":"%s"},"replyToken":"fake-private-failed-reply-token",
                "message":{"type":"text","id":"fictional-failed-message","text":"虚构失败回复"}}]}
                """.formatted(USER);
        assertThat(http.postForEntity("/line/webhook", new HttpEntity<>(body, signedHeaders(body)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        drain();
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
        assertThat(meters.get("line.push.failed").counter().count()).isEqualTo(pushBefore + 1);
        assertThat(meters.get("line.reply.failed").counter().count()).isEqualTo(replyBefore + 1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_log WHERE trace_id = ?", String.class,
                response.getHeaders().getFirst("X-Trace-Id"))).isEqualTo("FAILED");
        verify(client).pushMessage(any(), any());
        verify(client).replyMessage(any());
        assertThat(logs).doesNotContain("fake-private-upstream-key", "fake-private-failed-reply-token");
    }

    private HttpHeaders signedHeaders(String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("fake-flow-channel-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Line-Signature", Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))));
        return headers;
    }

    private List<JsonNode> events(String output, String traceId) throws Exception {
        var events = new ArrayList<JsonNode>();
        for (String line : output.lines().filter(value -> value.startsWith("{")).toList()) {
            var event = mapper.readTree(line);
            if (traceId.equals(event.path("trace_id").asText())) events.add(event);
        }
        return events;
    }

    private void drain() {
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(5)).until(aiScheduler::isIdle);
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookExecutor.getActiveCount() == 0
                && webhookExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount() == 0
                && persistenceExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }
}
