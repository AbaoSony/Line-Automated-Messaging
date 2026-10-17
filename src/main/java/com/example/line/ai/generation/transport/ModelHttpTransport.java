package com.example.line.ai.generation.transport;

import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.*;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import java.io.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import okio.BufferedSink;

/** 复用官方 SDK HTTP 契约/JSON，关联实际取消；关闭 OkHttp 重放和重定向，不输出报文。 */
public final class ModelHttpTransport implements HttpClient {
    private final okhttp3.OkHttpClient client;
    private final Set<Active> active=ConcurrentHashMap.newKeySet();
    private final Runnable terminalObserver;
    /** 创建无额外线程池、无报文日志的受控传输。 */
    public ModelHttpTransport(){this(() -> {});}
    ModelHttpTransport(Runnable terminalObserver){
        this.terminalObserver=terminalObserver;
        client=new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }
    /**
     * 同步契约复用相同可取消路径；业务适配使用异步入口和原截止。
     * @param request 官方已序列化请求
     * @param options 官方请求预算
     * @return 原始响应供 SDK 解析
     * @throws OpenAIIoException 传输或等待失败时抛出安全分类
     */
    @Override public HttpResponse execute(HttpRequest request,RequestOptions options){
        try{return executeAsync(request,options).get();}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new OpenAIIoException("Model transport interrupted",null);}
        catch(ExecutionException failure){throw new OpenAIIoException("Model transport failed",null);}
    }
    /**
     * 每次只发起一个实际 HTTP 请求，并将取消绑定到当前模型工作作用域。
     * @param request 官方业务 JSON 与外部认证头，仅用于当前网络请求
     * @param options 受原截止限制的超时
     * @return SDK 待解析响应，不自行重试
     * @throws IllegalStateException 缺失单次工作作用域时拒绝发起请求
     */
    @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request,RequestOptions options){
        var state=new Active();
        var scope=request.headers().values(ModelCallScope.HEADER);
        if(scope.size()!=1)throw new IllegalStateException("Missing model call scope");
        ModelCallScope.attach(scope.get(0),state::cancel,state.finished);
        try {
            var body=request.body();
            RequestBody outgoing=body==null?null:new RequestBody(){
                @Override public MediaType contentType(){return body.contentType()==null?null:MediaType.parse(body.contentType());}
                @Override public long contentLength(){return body.contentLength();}
                // 503 Retry-After:0 等底层恢复也会隐式重放，不能只关连接重试。
                @Override public boolean isOneShot(){return true;}
                @Override public void writeTo(BufferedSink sink)throws IOException{body.writeTo(sink.outputStream());}
            };
            var builder=new Request.Builder().url(request.url()).method(request.method().name(),outgoing);
            for(String name:request.headers().names())if(!name.equalsIgnoreCase(ModelCallScope.HEADER))
                for(String value:request.headers().values(name))builder.addHeader(name,value);
            Timeout timeout=options.getTimeout()==null?Timeout.builder().build():options.getTimeout();
            var http=client.newBuilder().connectTimeout(timeout.connect()).readTimeout(timeout.read()).writeTimeout(timeout.write())
                    .callTimeout(timeout.request()).eventListener(new EventListener(){
                        @Override public void callEnd(Call call){state.wireEnded();}
                        @Override public void callFailed(Call call,IOException failure){state.wireEnded();}
                    }).build();
            var future=new CompletableFuture<HttpResponse>();
            state.call=http.newCall(builder.build());active.add(state);
            if(state.cancelled.get())state.call.cancel();
            state.call.enqueue(new Callback(){
                @Override public void onFailure(Call call,IOException failure){
                    try{future.completeExceptionally(new OpenAIIoException("Model transport failed",null));}
                    finally{try{if(body!=null)body.close();}finally{state.callbackEnded();}}
                }
                @Override public void onResponse(Call call,Response response){
                    try {
                        var headers=Headers.builder();for(int i=0;i<response.headers().size();i++)headers.put(response.headers().name(i),response.headers().value(i));
                        HttpResponse result=new HttpResponse(){
                            @Override public int statusCode(){return response.code();}
                            @Override public Headers headers(){return headers.build();}
                            @Override public InputStream body(){return response.body().byteStream();}
                            @Override public void close(){response.close();}
                        };
                        if(!future.complete(result))result.close();
                    } finally{try{if(body!=null)body.close();}finally{state.callbackEnded();}}
                }
            });
            future.whenComplete((response,failure) -> {if(future.isCancelled())state.cancel();});
            return future;
        } catch(RuntimeException failure){state.abort();throw failure;}
    }
    /** @return 尚未退出真实传输/回调的请求数，仅用于无用户标签的观测与测试 */
    public int inFlight(){return active.size();}
    /** 关闭 SDK 时取消所有实际调用；生产停机先等待业务工作，不在此假报请求已经结束。 */
    @Override public void close(){client.dispatcher().cancelAll();client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
    private final class Active {
        final CountDownLatch finished=new CountDownLatch(1);
        final AtomicBoolean wire=new AtomicBoolean(),callback=new AtomicBoolean();
        final AtomicBoolean cancelled=new AtomicBoolean();
        volatile Call call;
        void cancel(){cancelled.set(true);var current=call;if(current!=null)current.cancel();}
        void wireEnded(){try{terminalObserver.run();}finally{wire.set(true);complete();}}
        void callbackEnded(){callback.set(true);complete();}
        void complete(){if(wire.get() && callback.get()){active.remove(this);finished.countDown();}}
        void abort(){cancel();wire.set(true);callback.set(true);complete();}
    }
}
