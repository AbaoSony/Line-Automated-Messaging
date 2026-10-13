package com.example.line;

import com.example.line.service.LineContentService;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "line.bot.channel-secret=documentation-test-secret",
        "line.bot.channel-token=documentation-test-token",
        "line.internal-push.enabled=true",
        "line.internal-push.token=documentation-internal-token"
})
@Import(DocumentedFlowIntegrationTest.LocalLineConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class DocumentedFlowIntegrationTest {
    private static final Path EXAMPLES = Path.of("docs", "examples");
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired private TestRestTemplate http;
    @Autowired private LocalLineStub stub;
    @Autowired private ThreadPoolTaskExecutor webhookExecutor;
    @Autowired private LineContentService contentService;

    @Test
    void documentedInboundAndOutboundDataMatchTheActualHttpFlow(CapturedOutput logs) throws Exception {
        byte[] text = Files.readAllBytes(EXAMPLES.resolve("webhook-text.json"));
        assertThat(send(text, false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stub.replies).isEmpty();

        assertThat(send(text, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        ReplyCapture capture = stub.replies.poll(5, TimeUnit.SECONDS);
        assertThat(capture).isNotNull();
        assertThat(capture.method()).isEqualTo("POST");
        assertThat(capture.authorization()).isEqualTo("Bearer documentation-test-token");
        assertThat(capture.body()).isEqualTo(readJson("reply-text-request.json"));

        assertThat(send(Files.readAllBytes(EXAMPLES.resolve("webhook-redelivery.json")), true)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitWorkers();
        assertThat(stub.replies).isEmpty();

        for (String name : List.of("image", "sticker", "location", "file")) {
            byte[] body = Files.readAllBytes(EXAMPLES.resolve("webhook-" + name + ".json"));
            assertThat(send(body, true).getStatusCode()).isEqualTo(HttpStatus.OK);
            capture = stub.replies.poll(5, TimeUnit.SECONDS);
            assertThat(capture).isNotNull();
            JsonNode incoming = mapper.readTree(body).path("events").get(0);
            assertThat(capture.body().path("replyToken").asText()).isEqualTo(incoming.path("replyToken").asText());
            assertThat(capture.body().path("messages").get(0).path("text").asText()).isEqualTo("暂不支持");
            if (name.equals("image")) {
                assertThat(capture.body()).isEqualTo(readJson("reply-image-request.json"));
            }
        }
        for (String name : List.of("follow", "unfollow", "postback", "empty")) {
            assertThat(send(Files.readAllBytes(EXAMPLES.resolve("webhook-" + name + ".json")), true)
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

        assertThat(send(Files.readAllBytes(EXAMPLES.resolve("webhook-hello.json")), true)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        capture = stub.replies.poll(5, TimeUnit.SECONDS);
        assertThat(capture).isNotNull();
        assertThat(capture.body()).isEqualTo(readJson("reply-hello-request.json"));

        stub.pushStatuses.addAll(List.of(503, 200));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", "documentation-internal-token");
        var pushRequest = new HttpEntity<>(Files.readAllBytes(EXAMPLES.resolve("internal-push-request.json")), headers);
        var pushResponse = http.postForEntity("/internal/push", pushRequest, String.class);
        assertThat(pushResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        PushCapture failedPush = stub.pushes.poll(5, TimeUnit.SECONDS);
        PushCapture acceptedPush = stub.pushes.poll(5, TimeUnit.SECONDS);
        assertThat(failedPush).isNotNull();
        assertThat(acceptedPush).isNotNull();
        assertThat(acceptedPush.retryKey()).isEqualTo(failedPush.retryKey());
        assertThat(java.util.UUID.fromString(acceptedPush.retryKey())).isNotNull();
        assertThat(acceptedPush.authorization()).isEqualTo("Bearer documentation-test-token");
        assertThat(failedPush.body()).isEqualTo(readJson("push-text-request.json")).isEqualTo(acceptedPush.body());
        assertThat(mapper.readTree(pushResponse.getBody()).path("retryKey").asText()).isEqualTo(acceptedPush.retryKey());

        stub.pushStatuses.add(409);
        pushResponse = http.postForEntity("/internal/push", pushRequest, String.class);
        assertThat(pushResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(pushResponse.getBody()).path("alreadyAccepted").asBoolean()).isTrue();
        assertThat(logs).doesNotContain("documentation-test-secret", "documentation-test-token",
                "documentation-internal-token", "Authorization: Bearer",
                "00000000000000000000000000000001", "你好，我想查询订单");
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
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookExecutor.getActiveCount() == 0
                && webhookExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

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

    record ReplyCapture(String method, String authorization, JsonNode body) {}
    record PushCapture(String retryKey, String authorization, JsonNode body) {}

    static class LocalLineStub implements AutoCloseable {
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
                            ? Files.readAllBytes(EXAMPLES.resolve("reply-success-response.json"))
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
                    byte[] response = Files.readAllBytes(EXAMPLES.resolve("reply-success-response.json"));
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
