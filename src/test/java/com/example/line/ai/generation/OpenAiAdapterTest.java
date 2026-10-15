package com.example.line.ai.generation;

import com.example.line.common.execution.Deadline;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;

import static org.assertj.core.api.Assertions.*;

/** 使用官方 SDK 和本地 HTTP Stub 验证实际序列化、隐藏重试关闭、错误分类和迟到响应。 */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiAdapterTest {
    private HttpServer server;
    private final BlockingQueue<Integer> statuses=new LinkedBlockingQueue<>();
    private final BlockingQueue<JsonNode> requests=new LinkedBlockingQueue<>();
    private final AtomicInteger calls=new AtomicInteger();
    private final ObjectMapper mapper=new ObjectMapper();
    private final CountDownLatch release=new CountDownLatch(1);
    private volatile boolean block;
    private com.openai.client.OpenAIClient client;

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {var thread=new Thread(r,"fictional-openai");thread.setDaemon(true);return thread;}));
        server.createContext("/v1/responses",exchange -> {
            calls.incrementAndGet();
            requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            if(block)try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            Integer status=statuses.poll();if(status==null)status=200;
            String body=status==200?"""
                    {"id":"resp_fictional","object":"response","created_at":1,"status":"completed","model":"gpt-6.1-sol",
                     "output":[{"id":"msg_fictional","type":"message","role":"assistant","status":"completed",
                      "content":[{"type":"output_text","text":"虛構模型回覆","annotations":[]}]}],
                     "usage":{"input_tokens":10,"output_tokens":20,"total_tokens":30,
                       "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":5}}}
                    """:"{\"error\":{\"message\":\"fake-private-upstream-api-key\",\"type\":\"fictional_error\",\"code\":\"fake\"}}";
            byte[] response=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            try {exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);}
            catch(java.io.IOException disconnected){ /* 本地超时会取消连接；Stub 只结束该响应，不重试或写外部状态。 */ }
            finally {exchange.close();}
        });
        server.start();
        client=OpenAIOkHttpClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .apiKey("fake-adapter-api-key").maxRetries(0).logLevel(LogLevel.OFF).build();
    }
    @AfterEach void close(){release.countDown();client.close();server.stop(0);}
    private ChatRequest request(Duration budget){return new ChatRequest("虛構 system",List.of(new ChatRequest.HistoryMessage("user","原始歷史"),
            new ChatRequest.HistoryMessage("assistant","原始回覆")),"當前輸入","fictional-id","fictional-correlation",Instant.EPOCH,Deadline.current(budget));}
    private OpenAiChatModelClient adapter(ModelProperties settings){return new OpenAiChatModelClient(client,settings,new SimpleMeterRegistry());}
    @Test void realSdkRequestPreservesRolesOmitsUnsupportedParametersAndReturnsUsage(CapturedOutput logs) throws Exception {
        ChatResult result=adapter(GenerationPolicyTest.settings(8192)).generate(request(Duration.ofSeconds(3)));
        assertThat(result.text()).isEqualTo("虛構模型回覆");assertThat(result.inputTokens()).isEqualTo(10);assertThat(result.outputTokens()).isEqualTo(20);
        assertThat(result.status()).isEqualTo("COMPLETED");
        JsonNode sent=requests.poll(2,TimeUnit.SECONDS);
        assertThat(sent.path("model").asText()).isEqualTo("gpt-6.1-sol");assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("low");
        assertThat(sent.path("store").asBoolean(true)).isFalse();assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.path("instructions").asText()).isEqualTo("虛構 system");
        assertThat(sent.path("input")).hasSize(3);
        assertThat(sent.path("input").get(0).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("input").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(sent.path("input").get(2).path("content").asText()).isEqualTo("當前輸入");
        assertThat(logs).doesNotContain("fake-adapter-api-key");
    }
    @ParameterizedTest @ValueSource(ints={400,401,408,409,429})
    void every4xxIsNotRetriedByApplicationOrSdk(int status,CapturedOutput logs){
        statuses.add(status);
        assertThatThrownBy(() -> adapter(GenerationPolicyTest.settings(8192)).generate(request(Duration.ofSeconds(3))))
                .isInstanceOf(ModelFailure.class).hasMessage("http_"+status);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(logs).doesNotContain("fake-private-upstream-api-key","fake-adapter-api-key");
    }
    @Test void serverFailureRetriesOnceWithSameBusinessRequest() throws Exception {
        statuses.add(503);statuses.add(200);
        assertThat(adapter(GenerationPolicyTest.settings(8192)).generate(request(Duration.ofSeconds(3))).text()).isEqualTo("虛構模型回覆");
        assertThat(calls.get()).isEqualTo(2);assertThat(requests.poll()).isEqualTo(requests.poll());
    }
    @Test void exhaustedServerFailureStopsAfterTwoAttempts(){
        statuses.add(503);statuses.add(503);statuses.add(200);
        assertThatThrownBy(() -> adapter(GenerationPolicyTest.settings(8192)).generate(request(Duration.ofSeconds(3))))
                .isInstanceOf(ModelFailure.class).hasMessage("http_503");
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void timeoutCancelsWaitingAndLateResponseCannotBecomeSuccessfulResult(){
        adapter(GenerationPolicyTest.settings(8192)).generate(request(Duration.ofSeconds(3)));
        calls.set(0);requests.clear();
        block=true;
        var props=new ModelProperties("gpt-6.1-sol","low",null,8192,1024,Duration.ofMillis(200),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofMillis(10));
        long start=System.nanoTime();
        assertThatThrownBy(() -> adapter(props).generate(request(Duration.ofMillis(200)))).isInstanceOf(ModelFailure.class);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        assertThat(calls.get()).isBetween(1,2);
    }
}
