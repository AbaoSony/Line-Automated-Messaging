package com.example.line.client;

import com.example.line.common.execution.LineSendScope;
import com.example.line.common.execution.UserWorkTracker;
import com.linecorp.bot.client.base.BotAwareJacksonConverter;
import com.linecorp.bot.client.base.ResultCallAdapterFactory;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiExceptionBuilder;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import okhttp3.*;
import retrofit2.Retrofit;

/** 使用锁定 SDK 的接口、序列化和错误适配，显式关闭 HTTP 隐式重放并跟踪实际传输清理。 */
public final class LineMessagingTransport implements AutoCloseable {
    private static final Duration SDK_TIMEOUT=Duration.ofSeconds(10);
    private static final int SDK_MAX_REQUESTS=64;
    private static final int SDK_MAX_REQUESTS_PER_HOST=5;
    private static final EventListener NO_OBSERVER=new EventListener() {};
    private final OkHttpClient http;
    private final MessagingApiClient client;
    /**
     * 装配官方接口，生产由 LineClientConfig 固定 LINE 地址，测试仅指向本地 Stub。
     * @param channelToken 外部注入的凭证，不提供对象打印或报文日志
     * @param endpoint 生产固定域名或本地模拟端点
     * @throws IllegalArgumentException 凭证缺失时抛出
     */
    public LineMessagingTransport(String channelToken,URI endpoint) { this(channelToken,endpoint,NO_OBSERVER); }
    LineMessagingTransport(String channelToken,URI endpoint,EventListener observer) {
        if(channelToken==null || channelToken.isBlank())throw new IllegalArgumentException("Missing LINE channel token");
        var dispatcher=new Dispatcher();dispatcher.setMaxRequests(SDK_MAX_REQUESTS);dispatcher.setMaxRequestsPerHost(SDK_MAX_REQUESTS_PER_HOST);
        var errors=new MessagingApiExceptionBuilder();
        // 一次性 Reply 不能接受连接恢复、408/503 Retry-After 或重定向的隐藏重放；Push 由应用使用同一 Key 重试。
        http=new OkHttpClient.Builder().dispatcher(dispatcher).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(SDK_TIMEOUT).readTimeout(SDK_TIMEOUT).writeTimeout(SDK_TIMEOUT)
                .eventListenerFactory(call -> new TransportListener(LineSendScope.retainTransport(),observer))
                .addInterceptor(chain -> {
                    var request=chain.request();
                    // retryOnConnectionFailure=false 仍不足以禁止 503 Retry-After 重放，正文也必须标为单次使用。
                    if(request.body()!=null)request=request.newBuilder().method(request.method(),new SingleUseBody(request.body())).build();
                    return chain.proceed(request.newBuilder().header("Authorization","Bearer "+channelToken).build());
                })
                .addInterceptor(chain -> {
                    var response=chain.proceed(chain.request());
                    if(response.isSuccessful())return response;
                    // 官方异常构造若遇到非法错误正文仍须关闭响应，否则连接清理和删除等待可能滞留。
                    try(response) { throw errors.build(response); }
                }).build();
        client=new Retrofit.Builder().baseUrl(endpoint.toString())
                .addConverterFactory(BotAwareJacksonConverter.create(ModelObjectMapper.createNewObjectMapper()))
                .addCallAdapterFactory(new ResultCallAdapterFactory()).client(http).build().create(MessagingApiClient.class);
    }
    /** @return 官方 SDK 消息接口，调用不经过任何自动 HTTP 重试 */
    public MessagingApiClient client() { return client; }
    /** 停机取消剩余传输并关闭本客户端拥有的执行器及空闲连接。 */
    @Override public void close() {
        http.dispatcher().cancelAll();http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();
    }
    private static final class SingleUseBody extends RequestBody {
        private final RequestBody body;
        SingleUseBody(RequestBody body) { this.body=body; }
        /** @return 不允许 HTTP 层自动再次写入本次正文 */
        @Override public boolean isOneShot() { return true; }
        /** @return 原 SDK 内容类型 */
        @Override public MediaType contentType() { return body.contentType(); }
        /** @return 原始正文长度；不重新序列化业务数据
         * @throws IOException 无法读取原始正文长度时抛出 */
        @Override public long contentLength() throws IOException { return body.contentLength(); }
        /** @param sink 原 HTTP 写入目标
         * @throws IOException 网络或原正文写入失败时抛出 */
        @Override public void writeTo(okio.BufferedSink sink) throws IOException { body.writeTo(sink); }
    }
    private static final class TransportListener extends EventListener {
        private final UserWorkTracker.Lease lease;
        private final EventListener observer;
        TransportListener(UserWorkTracker.Lease lease,EventListener observer) { this.lease=lease;this.observer=observer; }
        /** @param call 实际传输已结束的调用 */
        @Override public void callEnd(Call call) {
            try {observer.callEnd(call);}finally {if(lease!=null)lease.close();}
        }
        /** @param call 实际传输已失败的调用
         * @param failure 仅转交测试观察器，不记录原始错误 */
        @Override public void callFailed(Call call,IOException failure) {
            try {observer.callFailed(call,failure);}finally {if(lease!=null)lease.close();}
        }
    }
}
