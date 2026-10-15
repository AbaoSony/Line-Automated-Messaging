package com.example.line.webhook;

import com.example.line.webhook.dedup.EventDeduplicator;
import com.example.line.webhook.handler.EventDispatcher;
import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.LogEvents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.MessageEvent;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 串联 WebhookController 与异步业务：有界入队、SDK 解析、Reply 年龄保护、幂等和策略分发。
 * 与 EventDeduplicator、EventDispatcher 协作；所有耗时处理在专用 Executor 中执行，
 * 失败只记录安全摘要，不把已经返回的 HTTP 200 改为业务结果。
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private final ObjectMapper mapper = ModelObjectMapper.createNewObjectMapper();
    private final Executor executor;
    private final EventDispatcher dispatcher;
    private final EventDeduplicator deduplicator;
    private final com.example.line.common.execution.UserWorkTracker tracker;
    private final java.time.Duration totalTimeout;
    private final java.util.concurrent.atomic.AtomicLong receiptSequence=new java.util.concurrent.atomic.AtomicLong();

    /**
     * 注入异步执行和事件生命周期依赖。
     *
     * @param executor WebhookConfig 提供的有界执行器，不应替换为请求线程直接执行的实现
     * @param dispatcher 按具体事件 Class 调用 Handler 的分发器
     * @param deduplicator 以 webhookEventId 原子抢占、成功保留和失败释放的接口
     * @param tracker 用户删除屏障
     * @param properties 与 AI 一致的入口截止预算
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WebhookService(@Qualifier("webhookAdmissionExecutor") Executor executor,
            EventDispatcher dispatcher, EventDeduplicator deduplicator,com.example.line.common.execution.UserWorkTracker tracker,com.example.line.ai.AiProperties properties) {
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.deduplicator = deduplicator;
        this.tracker=tracker;
        this.totalTimeout=properties.totalTimeout();
    }

    /**
     * 为无容器的接入单元测试创建隔离跟踪器。
     * @param executor 测试有序执行器
     * @param dispatcher 测试策略分发
     * @param deduplicator 测试去重
     */
    public WebhookService(Executor executor,EventDispatcher dispatcher,EventDeduplicator deduplicator){
        this.executor=executor;this.dispatcher=dispatcher;this.deduplicator=deduplicator;
        this.tracker=new com.example.line.common.execution.UserWorkTracker();this.totalTimeout=java.time.Duration.ofSeconds(15);
    }

    /**
     * 提交已验签请求后立即返回，不等待解析或 LINE Reply，以满足 Webhook 快速确认要求。
     * 队列满/关闭的拒绝被捕获并记录，现有策略仍由 Controller 返回 200，拒绝任务不会执行。
     *
     * @param body 非 null 的已验签原始字节；调用方提交后不得再修改该数组
     */
    public synchronized void submit(byte[] body) {
        String captured=org.slf4j.MDC.get("received_nanos");
        long receivedNanos=captured==null?System.nanoTime():Long.parseLong(captured);
        org.slf4j.MDC.put("accepted_at",java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        try {
            executor.execute(() -> process(body, receivedNanos));
        } catch (RejectedExecutionException e) {
            BusinessLog.write(log, Level.ERROR, LogEvents.EXECUTOR_REJECTED,
                    "LINE webhook not queued; returning 200 per acknowledgement policy",
                    BusinessLog.fields("outcome", "SKIPPED"));
        }
    }

    // 保留直接处理入口供单元测试验证解析和分发，生产请求通过 submit 进入专用线程池。
    void process(byte[] body) {
        process(body, System.nanoTime());
    }

    void process(byte[] body, long receivedNanos) {
        final CallbackRequest request;
        try {
            request = mapper.readValue(body, CallbackRequest.class);
            if (request == null || request.events() == null) {
                BusinessLog.write(log, Level.WARN, LogEvents.WEBHOOK_PARSE_FAILED, "LINE webhook has no events array",
                        BusinessLog.fields("outcome", "SKIPPED"));
                return;
            }
        } catch (Exception e) {
            // Jackson exception messages may include request data. Never log them.
            // 解析异常常带请求片段，日志只保留异常类型，不能直接打印异常对象或消息。
            BusinessLog.failure(log, Level.WARN, LogEvents.WEBHOOK_PARSE_FAILED, "Cannot parse LINE webhook", e,
                    BusinessLog.fields("outcome", "FAILED"));
            return;
        }
        for (Event event : request.events()) {
            org.slf4j.MDC.put("received_sequence",Long.toString(receiptSequence.incrementAndGet()));
            try (var scope = LogContext.event(event == null ? null : event.webhookEventId(),
                    event == null || event.source() == null ? null : event.source().userId())) {
                processEvent(event, receivedNanos);
            }
        }
    }

    private void processEvent(Event event, long receivedNanos) {
        if (event == null || event.webhookEventId() == null || event.webhookEventId().isBlank()) {
            BusinessLog.write(log, Level.WARN, LogEvents.WEBHOOK_EVENT_SKIPPED, "Skipping LINE event without webhookEventId",
                    BusinessLog.fields("outcome", "SKIPPED", "reason", "missing_event_id"));
            return;
        }
        String id = event.webhookEventId();
        // Do not retain a short-lived replyToken behind slow/busy workers.
        // Measure from this HTTP delivery, not LINE timestamp (redeliveries retain it).
        // 15 秒是本项目保守的排队预算，不是 LINE 的官方有效期承诺；同批前置业务耗时也计入。
        if (event instanceof MessageEvent && System.nanoTime() - receivedNanos > totalTimeout.toNanos()) {
            BusinessLog.write(log, Level.WARN, LogEvents.WEBHOOK_EVENT_SKIPPED,
                    "Skipping stale queued reply; receipt age exceeds configured budget",
                    BusinessLog.fields("outcome", "SKIPPED", "reason", "stale_reply"));
            return;
        }
        boolean acquired = false;
        try {
            // isRedelivery 只描述此次投递；第一次见到的重投递仍应处理，去重必须看事件 ID。
            boolean redelivery = event.deliveryContext() != null
                    && Boolean.TRUE.equals(event.deliveryContext().isRedelivery());
            if (!deduplicator.tryAcquire(id)) {
                BusinessLog.write(log, Level.INFO, LogEvents.WEBHOOK_DUPLICATE, "Skipping duplicate LINE event",
                        BusinessLog.fields("outcome", "SKIPPED", "is_redelivery", redelivery));
                return;
            }
            acquired = true;
            BusinessLog.write(log, Level.INFO, LogEvents.WEBHOOK_RECEIVED, "Processing LINE event",
                    BusinessLog.fields("message_type", event.getClass().getSimpleName(), "is_redelivery", redelivery));
            var lease=tracker.register(event.source()==null?null:event.source().userId());
            if(lease==null){deduplicator.complete(id);return;}
            try {
                var decorator=new com.example.line.common.logging.MdcTaskDecorator();
                var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Runnable completed=decorator.decorate(() -> {
                    try(lease) {
                        Throwable failure=error.get();
                        if(failure==null)deduplicator.complete(id);
                        else {deduplicator.release(id);BusinessLog.failure(log,Level.ERROR,LogEvents.WEBHOOK_EVENT_FAILED,
                                "Async LINE event failed",failure,BusinessLog.fields("outcome","FAILED"));}
                    }
                });
                dispatcher.dispatchAsync(event).whenComplete((ignored,failure) -> {error.set(failure);completed.run();});
            } catch(Exception failure){lease.close();throw failure;}
        } catch (Exception e) {
            // 只释放自己取得的占用；缓存满等抢占前失败不能删除其他任务的记录。
            if (acquired) {
                deduplicator.release(id);
            }
            BusinessLog.failure(log, Level.ERROR, LogEvents.WEBHOOK_EVENT_FAILED,
                    "LINE event failed; future delivery may retry", e, BusinessLog.fields("outcome", "FAILED"));
        }
    }
}
