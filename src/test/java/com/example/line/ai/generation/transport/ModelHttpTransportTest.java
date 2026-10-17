package com.example.line.ai.generation.transport;

import com.example.line.ai.generation.*;
import com.example.line.ai.decision.*;
import com.example.line.ai.aggregation.InboundMember;
import com.example.line.chat.PrivateHistory;
import com.example.line.common.execution.*;
import com.openai.client.*;
import com.openai.core.*;
import com.openai.core.http.*;
import com.openai.models.responses.ResponseCreateParams;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 真实 SDK/HTTP 验证取消直到本地传输及回调退出，不能提前释放删除或后续物理调用额度。 */
class ModelHttpTransportTest {
    @Test void cancellationBeforeAsyncSdkDispatchWaitsForThatWorkAndPreventsLateHttpInvocation()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var transport=new ModelHttpTransport();
        HttpClient delayed=new HttpClient(){
            @Override public HttpResponse execute(HttpRequest request,RequestOptions options){throw new UnsupportedOperationException();}
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request,RequestOptions options){
                entered.countDown();awaitIgnoringInterrupt(release);return transport.executeAsync(request,options);
            }
            @Override public void close(){transport.close();}
        };
        var sdk=new OpenAIClientImpl(ClientOptions.builder().httpClient(delayed).baseUrl("http://127.0.0.1:1/v1")
                .apiKey("fictional-dispatch-key").maxRetries(0).logLevel(LogLevel.OFF).build());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var props=Binder.get(new MockEnvironment()).bindOrCreate("line.ai.model",ModelProperties.class);
            var result=pool.submit(() -> assertThatThrownBy(() -> new OpenAiChatModelClient(sdk,props,new SimpleMeterRegistry()).generate(
                    new ChatRequest("虛構設定",List.of(),"完整虛構原文","message","event",Instant.EPOCH,Deadline.current(Duration.ofMillis(200)))))
                    .isInstanceOf(ModelFailure.class));
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(350,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(transport.inFlight()).isZero();release.countDown();result.get(3,TimeUnit.SECONDS);
            assertThat(transport.inFlight()).isZero();
        }finally{release.countDown();pool.shutdownNow();sdk.close();}
    }
    @Test void sdkReturnedFutureCancellationDoesNotPropagateThroughItsCompositionToRawTransportFuture()throws Exception{
        var transport=mock(HttpClient.class);var raw=new CompletableFuture<HttpResponse>();
        var dispatched=new CountDownLatch(1);
        when(transport.executeAsync(any(),any())).thenAnswer(call -> {dispatched.countDown();return raw;});
        var sdk=new OpenAIClientImpl(ClientOptions.builder().httpClient(transport).apiKey("fictional-cancel-key").maxRetries(0).logLevel(LogLevel.OFF).build());
        try{
            var exposed=sdk.async().responses().create(ResponseCreateParams.builder().model("gpt-6.1-sol").input("fictional input").build());
            assertThat(dispatched.await(3,TimeUnit.SECONDS)).isTrue();
            verify(transport).executeAsync(any(),any());assertThat(exposed.cancel(true)).isTrue();
            assertThat(raw.isCancelled()).isFalse();assertThat(raw.isDone()).isFalse();
        }finally{sdk.close();}
    }
    @ParameterizedTest @ValueSource(strings={"body-timeout","policy-timeout","policy-interrupted"})
    void timeoutOrCancellationKeepsWorkAndDeletionBlockedUntilActualTerminalCallbackExit(String scenario)throws Exception{
        var received=new CountDownLatch(1);var serverRelease=new CountDownLatch(1);
        var terminalEntered=new CountDownLatch(1);var terminalRelease=new CountDownLatch(1);var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var serverPool=Executors.newCachedThreadPool(r -> {var t=new Thread(r,"fictional-cancel-http");t.setDaemon(true);return t;});
        server.setExecutor(serverPool);server.createContext("/v1/responses",exchange -> {
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();received.countDown();
            awaitIgnoringInterrupt(serverRelease);
            try{exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes());}
            catch(java.io.IOException closed){/* 取消仅结束本地传输，Stub 不补发。 */}finally{exchange.close();}
        });server.start();
        var transport=new ModelHttpTransport(() -> {terminalEntered.countDown();awaitIgnoringInterrupt(terminalRelease);});
        var sdk=new OpenAIClientImpl(ClientOptions.builder().httpClient(transport).baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .apiKey("fictional-cancel-key").maxRetries(0).logLevel(LogLevel.OFF).build());
        var worker=Executors.newSingleThreadExecutor();var tracker=new UserWorkTracker();var runningThread=new AtomicReference<Thread>();
        try {
            var model=new ModelProperties("gpt-6-astra","max",null,8192,4096,Duration.ofMillis(300),Duration.ofMillis(300),Duration.ofMillis(100),Duration.ofMillis(10));
            var env=new MockEnvironment().withProperty("line.ai.decision.attempt-timeout",scenario.endsWith("interrupted")?"2s":"300ms");
            var policy=new OpenAiTimingModelClient(sdk,model,Binder.get(env).bindOrCreate("line.ai.decision",DecisionProperties.class));
            var finished=worker.submit(() -> {
                runningThread.set(Thread.currentThread());
                try(var lease=tracker.register("Uxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx7")){
                    assertThat(lease).isNotNull();
                    assertThatThrownBy(() -> {
                        if(scenario.startsWith("body"))new OpenAiChatModelClient(sdk,model,new SimpleMeterRegistry()).generate(new ChatRequest("虛構設定",List.of(),
                                "完整虛構正文輸入","message","event",Instant.EPOCH,Deadline.current(Duration.ofSeconds(3))));
                        else policy.decide(new TimingRequest(List.of(new InboundMember("Uxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx7","event","message","完整虛構策略輸入",
                                Instant.EPOCH,Instant.EPOCH,1,"trace")),new PrivateHistory(List.of(),null),0,null,Duration.ofSeconds(3),Deadline.current(Duration.ofSeconds(3))));
                    }).isInstanceOfAny(ModelFailure.class,TimingFailure.class);
                }
            });
            assertThat(received.await(3,TimeUnit.SECONDS)).isTrue();
            if(scenario.endsWith("interrupted"))runningThread.get().interrupt();
            assertThat(terminalEntered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(finished.isDone()).isFalse();assertThat(transport.inFlight()).isEqualTo(1);assertThat(calls).hasValue(1);
            try(var barrier=tracker.block("Uxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx7")){
                assertThatThrownBy(() -> barrier.await(Duration.ofMillis(50))).isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
                terminalRelease.countDown();finished.get(3,TimeUnit.SECONDS);barrier.await(Duration.ofSeconds(3));
            }
            await().atMost(Duration.ofSeconds(2)).until(() -> transport.inFlight()==0);assertThat(calls).hasValue(1);
        }finally{terminalRelease.countDown();serverRelease.countDown();worker.shutdownNow();sdk.close();server.stop(0);serverPool.shutdownNow();}
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch){
        boolean interrupted=false;
        while(true)try{if(!latch.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Fictional callback was not released");break;}
        catch(InterruptedException failure){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
}
