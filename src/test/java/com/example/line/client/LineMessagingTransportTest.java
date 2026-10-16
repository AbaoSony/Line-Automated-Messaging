package com.example.line.client;

import com.example.line.messaging.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import okhttp3.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 SDK 到本地 HTTP，不能由底层重试或重定向偷偷重放一次性 Reply。 */
class LineMessagingTransportTest {
    @Test void cancellationKeepsUserWorkUntilActualTransportCallbackExits() throws Exception {
        var cleanup=new CountDownLatch(1);var release=new CountDownLatch(1);var received=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            exchange.getRequestBody().readAllBytes();received.countDown();
            try {release.await(4,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            exchange.close();
        });server.start();
        var observer=new EventListener() {
            @Override public void callFailed(Call call,java.io.IOException failure) {
                cleanup.countDown();
                try {assertThat(release.await(4,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
        };
        var tracker=new com.example.line.common.execution.UserWorkTracker();var worker=Executors.newSingleThreadExecutor();
        String user="Ufictional-transport-cleanup";
        try {
            try(var transport=new LineMessagingTransport("fictional-transport-access",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),observer)) {
            var client=transport.client();
            var service=new LineMessageService(client,new LineApiCaller(new LineSendProperties(1,Duration.ofMillis(200),Duration.ofMillis(1))),tracker);
            var result=worker.submit(() -> {
                try(var scope=com.example.line.common.logging.LogContext.user(user)) {service.replyText("fictional-once-reply","完整虛構回覆");}
            });
            assertThat(received.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(cleanup.await(2,TimeUnit.SECONDS)).isTrue();
            try(var barrier=tracker.block(user)) {
                assertThatThrownBy(() -> barrier.await(Duration.ofMillis(30))).hasMessageContaining("timed out");
                release.countDown();barrier.await(Duration.ofSeconds(2));
            }
            }
        } finally {release.countDown();worker.shutdownNow();server.stop(0);}
    }
    @ParameterizedTest @ValueSource(ints={408,503,307})
    void replyIsNotReplayedForRetryAfterOrRedirect(int status) throws Exception {
        var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            exchange.getRequestBody().readAllBytes();int attempt=count.incrementAndGet();
            if(attempt==1) {
                exchange.getResponseHeaders().add("Retry-After","0");
                exchange.getResponseHeaders().add("Location","/replayed");
            }
            byte[] response=(attempt==1?"{\"message\":\"fictional-private-envelope\"}":"{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(attempt==1?status:200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            try(var transport=new LineMessagingTransport("fictional-transport-access",URI.create("http://127.0.0.1:"+server.getAddress().getPort()))) {
            var client=transport.client();
            var service=new LineMessageService(client,new LineApiCaller(new LineSendProperties(3,Duration.ofSeconds(2),Duration.ofMillis(1))));
            assertThatThrownBy(() -> service.replyText("fictional-once-reply","完整虛構回覆"))
                    .isInstanceOf(LineSendException.class);
            assertThat(count.get()).isEqualTo(1);
            }
        } finally {server.stop(0);}
    }
}
