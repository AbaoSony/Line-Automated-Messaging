package com.example.line.ai;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 只在测试内存保存虚构 HTTP 信封，用可控响应验证真实 LINE SDK 的发送事实。 */
final class ReplyHttpStub implements AutoCloseable {
    enum Mode { NORMAL, HOLD, RESET, BAD_JSON }
    static final class Plan {
        final int status;final Mode mode;
        final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        Plan(int status,Mode mode){this.status=status;this.mode=mode;}
    }
    record Capture(String method,String path,String authorization,JsonNode body) {}
    final BlockingQueue<Capture> requests=new LinkedBlockingQueue<>();
    final BlockingQueue<Plan> plans=new LinkedBlockingQueue<>();
    final AtomicInteger pushes=new AtomicInteger();
    private final ObjectMapper mapper=new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService workers=Executors.newCachedThreadPool(r -> {var thread=new Thread(r,"fictional-line-reply");thread.setDaemon(true);return thread;});
    ReplyHttpStub() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
        server.createContext("/",exchange -> {
            Plan plan=plans.poll();if(plan==null)plan=new Plan(200,Mode.NORMAL);
            try {
                requests.add(new Capture(exchange.getRequestMethod(),exchange.getRequestURI().getPath(),exchange.getRequestHeaders().getFirst("Authorization"),mapper.readTree(exchange.getRequestBody())));
                if(exchange.getRequestURI().getPath().contains("push"))pushes.incrementAndGet();
                plan.received.countDown();
                if(plan.mode==Mode.HOLD)try {plan.release.await(8,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                if(plan.mode==Mode.RESET)return;
                String body=plan.mode==Mode.BAD_JSON?"fictional-invalid-json":plan.status==200?"{}":"{\"message\":\"fictional-private-http-envelope\"}";
                byte[] response=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.getResponseHeaders().add("Retry-After","0");exchange.getResponseHeaders().add("X-Line-Accepted-Request-Id","fictional-accepted-header");
                if(plan.status==307)exchange.getResponseHeaders().add("Location","/redirected");
                try {exchange.sendResponseHeaders(plan.status,response.length);exchange.getResponseBody().write(response);}
                catch(IOException disconnected){ /* 超时/取消可已关闭本地连接；远端收到请求仍不能据此认定未执行。 */ }
            } finally {exchange.close();plan.finished.countDown();}
        });server.start();
    }
    Plan plan(int status,Mode mode){var plan=new Plan(status,mode);plans.add(plan);return plan;}
    URI endpoint(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
    @Override public void close(){plans.forEach(plan -> plan.release.countDown());server.stop(0);workers.shutdownNow();}
}
