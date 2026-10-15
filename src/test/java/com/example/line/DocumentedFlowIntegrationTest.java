package com.example.line;

import com.example.line.content.LineContentService;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.chat.ChatHistoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Executable documentation: real inbound HTTP -> worker -> SDK -> local HTTP stub. */
/** 使用文档样例验证真实 HTTP 入站、异步分发、SDK 出站、幂等重试和敏感日志边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "line.bot.channel-secret=documentation-test-secret",
        "line.bot.channel-token=documentation-test-token",
        "line.internal-push.enabled=true",
        "line.internal-push.token=documentation-internal-token"
})
@Import(DocumentedFlowIntegrationTest.LocalLineConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class DocumentedFlowIntegrationTest extends PostgresIntegrationSupport {
    private static final Path EXAMPLES = Path.of("docs", "examples");
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired private TestRestTemplate http;
    @Autowired private LocalLineStub stub;
    @Autowired private ThreadPoolTaskExecutor webhookExecutor;
    @Autowired private ThreadPoolTaskExecutor webhookAdmissionExecutor;
    @Autowired private com.example.line.ai.coordination.SessionScheduler aiScheduler;
    @Autowired private LineContentService contentService;
    @Autowired private ChatHistoryService chatHistory;
    @Autowired private ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired private com.example.line.common.logging.EventRecorder eventRecorder;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

    // 场景：完整验证验签、echo、事件去重、四类兜底、内容下载、Push 同 Key 重试与已接受冲突。
    @Test
    void documentedInboundAndOutboundDataMatchTheActualHttpFlow(CapturedOutput logs) throws Exception {
        byte[] text = Files.readAllBytes(EXAMPLES.resolve("webhook/webhook-text.json"));
        var invalidResponse = send(text, false);
        assertThat(invalidResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(java.util.UUID.fromString(invalidResponse.getHeaders().getFirst("X-Trace-Id"))).isNotNull();
        assertThat(stub.replies).isEmpty();

        var textResponse = send(text, true);
        assertThat(textResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String traceId = textResponse.getHeaders().getFirst("X-Trace-Id");
        assertThat(java.util.UUID.fromString(traceId)).isNotNull();
        assertThat(traceId).isNotEqualTo(invalidResponse.getHeaders().getFirst("X-Trace-Id"));
        ReplyCapture capture = stub.replies.poll(5, TimeUnit.SECONDS);
        assertThat(capture).isNotNull();
        assertThat(capture.method()).isEqualTo("POST");
        assertThat(capture.authorization()).isEqualTo("Bearer documentation-test-token");
        assertThat(capture.body()).isEqualTo(readJson("reply/reply-text-request.json"));
        awaitWorkers();
        awaitPersistence();
        JsonNode textEvent = mapper.readTree(text).path("events").get(0);
        String userId = textEvent.path("source").path("userId").asText();
        var history = chatHistory.findRecent(userId, 100);
        assertThat(history).filteredOn(row -> "INBOUND".equals(row.direction())
                && textEvent.path("message").path("id").asText().equals(row.lineMessageId()))
                .singleElement().satisfies(row -> {
                    assertThat(row.userId()).isEqualTo(userId);
                    assertThat(row.content().path("text").asText()).isEqualTo(textEvent.path("message").path("text").asText());
                    assertThat(row.content().has("quoteToken")).isFalse();
                });
        assertThat(history).anySatisfy(row -> {
            assertThat(row.direction()).isEqualTo("OUTBOUND");
            assertThat(row.content().path("text").asText()).isEqualTo(textEvent.path("message").path("text").asText());
        });
        var aiExample=readJson("reply/ai-result.json");
        var aiRow=jdbc.queryForMap("SELECT * FROM chat_message WHERE user_id=? AND in_reply_to_message_id=? AND direction='OUTBOUND'",
                userId,textEvent.path("message").path("id").asText());
        for(String field:List.of("model_name","reply_kind","source_type","conversation_id","in_reply_to_message_id","correlation_id"))
            assertThat(aiRow.get(field)).isEqualTo(aiExample.path(field).asText());
        for(String field:List.of("input_tokens","output_tokens","latency_ms"))
            assertThat(((Number)aiRow.get(field)).longValue()).isEqualTo(aiExample.path(field).asLong());
        assertThat(mapper.readTree(aiRow.get("content").toString()).path("text")).isEqualTo(aiExample.path("text"));
        var modelRequest=org.mockito.ArgumentCaptor.forClass(com.example.line.ai.generation.ChatRequest.class);
        org.mockito.Mockito.verify(testModel).generate(modelRequest.capture());
        assertThat(modelRequest.getValue().userMessage()).isEqualTo(textEvent.path("message").path("text").asText());
        assertThat(modelRequest.getValue().systemPrompt()).contains("小晴").doesNotContain("{{current_time}}","{{last_chat_gap}}",modelRequest.getValue().userMessage());
        assertThat(modelRequest.getValue().history()).isEmpty();

        assertThat(send(Files.readAllBytes(EXAMPLES.resolve("webhook/webhook-redelivery.json")), true)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitWorkers();
        awaitPersistence();
        assertThat(stub.replies).isEmpty();
        assertThat(chatHistory.findRecent(userId, 100)).filteredOn(row -> "INBOUND".equals(row.direction())
                && textEvent.path("message").path("id").asText().equals(row.lineMessageId())).hasSize(1);

        for (String name : List.of("image", "sticker", "location", "file")) {
            byte[] body = Files.readAllBytes(EXAMPLES.resolve("webhook/webhook-" + name + ".json"));
            assertThat(send(body, true).getStatusCode()).isEqualTo(HttpStatus.OK);
            capture = stub.replies.poll(5, TimeUnit.SECONDS);
            assertThat(capture).isNotNull();
            JsonNode incoming = mapper.readTree(body).path("events").get(0);
            assertThat(capture.body().path("replyToken").asText()).isEqualTo(incoming.path("replyToken").asText());
            assertThat(capture.body().path("messages").get(0).path("text").asText()).isEqualTo("暂不支持");
            if (name.equals("image")) {
                assertThat(capture.body()).isEqualTo(readJson("reply/reply-image-request.json"));
            }
        }
        for (String name : List.of("follow", "unfollow", "postback", "empty")) {
            assertThat(send(Files.readAllBytes(EXAMPLES.resolve("webhook/webhook-" + name + ".json")), true)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        awaitWorkers();
        assertThat(stub.replies).isEmpty();

        var output = new ByteArrayOutputStream();
        assertThat(contentService.downloadTo("537629183902005", output))
                .isEqualTo(LocalLineStub.CONTENT.length);
        assertThat(output.toByteArray()).isEqualTo(LocalLineStub.CONTENT);
        assertThat(stub.contentAuthorization.get()).isEqualTo("Bearer documentation-test-token");
        assertThat(stub.contentPath.get()).isEqualTo("/v2/bot/message/537629183902005/content");

        assertThat(send(Files.readAllBytes(EXAMPLES.resolve("webhook/webhook-hello.json")), true)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        capture = stub.replies.poll(5, TimeUnit.SECONDS);
        assertThat(capture).isNotNull();
        assertThat(capture.body()).isEqualTo(readJson("reply/reply-hello-request.json"));

        stub.pushStatuses.addAll(List.of(503, 200));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", "documentation-internal-token");
        var pushRequest = new HttpEntity<>(Files.readAllBytes(EXAMPLES.resolve("push/internal-push-request.json")), headers);
        var pushResponse = http.postForEntity("/internal/push", pushRequest, String.class);
        assertThat(pushResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        PushCapture failedPush = stub.pushes.poll(5, TimeUnit.SECONDS);
        PushCapture acceptedPush = stub.pushes.poll(5, TimeUnit.SECONDS);
        assertThat(failedPush).isNotNull();
        assertThat(acceptedPush).isNotNull();
        assertThat(acceptedPush.retryKey()).isEqualTo(failedPush.retryKey());
        assertThat(java.util.UUID.fromString(acceptedPush.retryKey())).isNotNull();
        assertThat(acceptedPush.authorization()).isEqualTo("Bearer documentation-test-token");
        assertThat(failedPush.body()).isEqualTo(readJson("push/push-text-request.json")).isEqualTo(acceptedPush.body());
        assertThat(mapper.readTree(pushResponse.getBody()).path("retryKey").asText()).isEqualTo(acceptedPush.retryKey());

        stub.pushStatuses.add(409);
        pushResponse = http.postForEntity("/internal/push", pushRequest, String.class);
        assertThat(pushResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(pushResponse.getBody()).path("alreadyAccepted").asBoolean()).isTrue();
        assertThat(logs).doesNotContain("documentation-test-secret", "documentation-test-token",
                "documentation-internal-token", "Authorization: Bearer",
                "00000000000000000000000000000001");
        assertThat(logs).contains("你好，我想查询订单", userId, traceId, "line.reply.sent", "user.message.received");
        assertThat(logs).doesNotContain(textEvent.path("message").path("quoteToken").asText());
        await().atMost(Duration.ofSeconds(5)).until(eventRecorder::isIdle);
        var storedEvent = jdbc.queryForMap("SELECT * FROM event_log WHERE trace_id = ? AND event = 'user.message.received'", traceId);
        var generatedDetail=mapper.readTree(jdbc.queryForObject("SELECT detail::text FROM event_log WHERE trace_id=? AND event='ai.generate.success'",String.class,traceId));
        assertThat(generatedDetail.path("content").has("incomplete_reason")).isTrue();
        assertThat(generatedDetail.path("content").path("incomplete_reason").isNull()).isTrue();
        assertThat(generatedDetail.path("content").has("reasoning_tokens")).isTrue();
        assertThat(generatedDetail.path("content").path("reasoning_tokens").isNull()).isTrue();
        assertThat(storedEvent.get("user_id")).isEqualTo(userId);
        assertThat(storedEvent.get("webhook_event_id")).isEqualTo(textEvent.path("webhookEventId").asText());
        assertThat(mapper.readTree(storedEvent.get("detail").toString()).path("content").path("text").asText())
                .isEqualTo(textEvent.path("message").path("text").asText());
        var eventExample = readJson("webhook/event-log.json");
        for (String column : List.of("event", "level", "webhook_event_id", "user_id", "outcome", "message_type")) {
            assertThat(storedEvent.get(column)).isEqualTo(eventExample.path(column).asText());
        }
        assertThat(mapper.readTree(storedEvent.get("detail").toString())).isEqualTo(eventExample.path("detail"));
        var storedAudit = jdbc.queryForMap("SELECT * FROM audit_log WHERE trace_id = ?", pushResponse.getHeaders().getFirst("X-Trace-Id"));
        assertThat(storedAudit.get("event")).isEqualTo("internal.push.invoked");
        assertThat(storedAudit.get("operator")).isEqualTo("internal-token");
        assertThat(storedAudit.get("source_ip")).isNotNull();
        assertThat(storedAudit.get("target_user_id")).isEqualTo(readJson("push/internal-push-request.json").path("userId").asText());
        assertThat(storedAudit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(mapper.readTree(storedAudit.get("detail").toString()).path("content").path("text").asText())
                .isEqualTo(readJson("push/internal-push-request.json").path("text").asText());
        var auditExample = readJson("push/audit-log.json");
        for (String column : List.of("event", "operator", "target_user_id", "outcome")) {
            assertThat(storedAudit.get(column)).isEqualTo(auditExample.path(column).asText());
        }
        assertThat(mapper.readTree(storedAudit.get("detail").toString())).isEqualTo(auditExample.path("detail"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE event = 'persist.failed'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT detail::text FROM event_log", String.class).toString())
                .doesNotContain("documentation-test-secret", "documentation-test-token", "documentation-internal-token",
                        textEvent.path("replyToken").asText(), textEvent.path("message").path("quoteToken").asText());
        assertThat(jdbc.queryForList("SELECT detail::text FROM audit_log", String.class).toString())
                .doesNotContain("documentation-test-token", "documentation-internal-token");
        assertThat(meters.get("webhook.requests").counter().count()).isEqualTo(12);
        assertThat(meters.get("webhook.signature.failed").counter().count()).isEqualTo(1);
        assertThat(meters.get("webhook.dedup.hits").counter().count()).isEqualTo(1);
        assertThat(meters.get("line.push.failed").counter().count()).isZero();

        // 静默仍完成当前事件，保留完整入站和失败依据，不把未发送提示写成成功出站。
        org.mockito.Mockito.doThrow(new com.example.line.ai.generation.ModelFailure("timeout")).when(testModel).generate(org.mockito.ArgumentMatchers.any());
        byte[] silent=Files.readAllBytes(EXAMPLES.resolve("webhook/webhook-silent-technical.json"));
        var silentResponse=send(silent,true);assertThat(silentResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitWorkers();awaitPersistence();await().atMost(Duration.ofSeconds(5)).until(eventRecorder::isIdle);
        var skippedExample=readJson("webhook/ai-reply-skipped.json");
        var skipped=jdbc.queryForMap("SELECT * FROM event_log WHERE trace_id=? AND event='ai.reply.skipped'",silentResponse.getHeaders().getFirst("X-Trace-Id"));
        for(String column:List.of("event","level","webhook_event_id","user_id","outcome"))
            assertThat(skipped.get(column)).isEqualTo(skippedExample.path(column).asText());
        assertThat(mapper.readTree(skipped.get("detail").toString())).isEqualTo(skippedExample.path("detail"));
        assertThat(stub.replies).isEmpty();
        var silentEvent=mapper.readTree(silent).path("events").get(0);
        var silentHistory=chatHistory.findRecent(silentEvent.path("source").path("userId").asText(),100);
        assertThat(silentHistory).singleElement().satisfies(row -> {
            assertThat(row.direction()).isEqualTo("INBOUND");
            assertThat(row.content().path("text")).isEqualTo(silentEvent.path("message").path("text"));
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE trace_id=? AND event='ai.generate.failed'",Integer.class,silentResponse.getHeaders().getFirst("X-Trace-Id"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE trace_id=? AND event='ai.fallback.used'",Integer.class,silentResponse.getHeaders().getFirst("X-Trace-Id"))).isZero();
        assertThat(send(silent,true).getStatusCode()).isEqualTo(HttpStatus.OK);awaitWorkers();assertThat(stub.replies).isEmpty();
    }

    private ResponseEntity<String> send(byte[] body, boolean correctSignature) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("documentation-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Line-Signature", correctSignature
                ? Base64.getEncoder().encodeToString(mac.doFinal(body))
                : Base64.getEncoder().encodeToString(new byte[32]));
        return http.postForEntity("/line/webhook", new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode readJson(String name) throws IOException {
        return mapper.readTree(Files.readAllBytes(EXAMPLES.resolve(name)));
    }

    private void awaitWorkers() {
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(5)).until(aiScheduler::isIdle);
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookExecutor.getActiveCount() == 0
                && webhookExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private void awaitPersistence() {
        await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount() == 0
                && persistenceExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    /** 仅在测试中把真实 SDK 客户端指向本地模拟服务器，生产目标地址保持不变。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalLineConfig {
        @Bean(destroyMethod = "close")
        LocalLineStub localLineStub() throws IOException { return new LocalLineStub(); }

        @Bean
        @Primary
        MessagingApiClient localMessagingClient(LocalLineStub stub) {
            return MessagingApiClient.builder("documentation-test-token").apiEndPoint(stub.endpoint())
                    .usingDefaultLogger(false).build();
        }

        @Bean
        @Primary
        MessagingApiBlobClient localBlobClient(LocalLineStub stub) {
            return MessagingApiBlobClient.builder("documentation-test-token").apiEndPoint(stub.endpoint())
                    .usingDefaultLogger(false).build();
        }
    }

    /** 仅在测试内存中捕获 Reply 的 HTTP 字段，供断言而不是输出到日志。 */
    record ReplyCapture(String method, String authorization, JsonNode body) {}
    /** 仅在测试内存中关联 Push Retry Key、鉴权字段与消息体以检查重试一致性。 */
    record PushCapture(String retryKey, String authorization, JsonNode body) {}

    /** 模拟 LINE 接受、瞬时失败、幂等冲突与内容流响应，避免测试访问真实 LINE。 */
    static class LocalLineStub implements AutoCloseable {
        // 这是流传输断言用的虚构字节，不是真实图片或 PDF，也不用于验证内容格式。
        static final byte[] CONTENT = "mock-binary-content".getBytes(StandardCharsets.UTF_8);
        final BlockingQueue<ReplyCapture> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<PushCapture> pushes = new LinkedBlockingQueue<>();
        final ConcurrentLinkedQueue<Integer> pushStatuses = new ConcurrentLinkedQueue<>();
        final AtomicReference<String> contentPath = new AtomicReference<>();
        final AtomicReference<String> contentAuthorization = new AtomicReference<>();
        private final HttpServer server;

        LocalLineStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/bot/message/push", exchange -> {
                try {
                    var body = new ObjectMapper().readTree(exchange.getRequestBody());
                    pushes.add(new PushCapture(exchange.getRequestHeaders().getFirst("X-Line-Retry-Key"),
                            exchange.getRequestHeaders().getFirst("Authorization"), body));
                    Integer plannedStatus = pushStatuses.poll();
                    int status = plannedStatus == null ? 200 : plannedStatus;
                    byte[] response = status == 200
                            ? Files.readAllBytes(EXAMPLES.resolve("reply/reply-success-response.json"))
                            : "{\"message\":\"simulated upstream failure\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (status == 409) exchange.getResponseHeaders().set("X-Line-Accepted-Request-Id", "accepted-request");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            server.createContext("/v2/bot/message/reply", exchange -> {
                try {
                    var body = new ObjectMapper().readTree(exchange.getRequestBody());
                    byte[] response = Files.readAllBytes(EXAMPLES.resolve("reply/reply-success-response.json"));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("X-Line-Request-Id", "mock-request-id");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    replies.add(new ReplyCapture(exchange.getRequestMethod(),
                            exchange.getRequestHeaders().getFirst("Authorization"), body));
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/v2/bot/message/", exchange -> {
                try {
                    contentPath.set(exchange.getRequestURI().getPath());
                    contentAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, CONTENT.length);
                    exchange.getResponseBody().write(CONTENT);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        @Override public void close() { server.stop(0); }
    }
}
