package com.example.line.webhook;

import com.example.line.webhook.dedup.EventDeduplicator;
import com.example.line.webhook.handler.EventDispatcher;

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

    /**
     * 注入异步执行和事件生命周期依赖。
     *
     * @param executor WebhookConfig 提供的有界执行器，不应替换为请求线程直接执行的实现
     * @param dispatcher 按具体事件 Class 调用 Handler 的分发器
     * @param deduplicator 以 webhookEventId 原子抢占、成功保留和失败释放的接口
     */
    public WebhookService(@Qualifier("webhookExecutor") Executor executor,
            EventDispatcher dispatcher, EventDeduplicator deduplicator) {
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.deduplicator = deduplicator;
    }

    /**
     * 提交已验签请求后立即返回，不等待解析或 LINE Reply，以满足 Webhook 快速确认要求。
     * 队列满/关闭的拒绝被捕获并记录，现有策略仍由 Controller 返回 200，拒绝任务不会执行。
     *
     * @param body 非 null 的已验签原始字节；调用方提交后不得再修改该数组
     */
    public void submit(byte[] body) {
        long receivedNanos = System.nanoTime();
        try {
            executor.execute(() -> process(body, receivedNanos));
        } catch (RejectedExecutionException e) {
            log.error("LINE webhook not queued; returning 200 per acknowledgement policy");
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
                log.warn("LINE webhook has no events array");
                return;
            }
        } catch (Exception e) {
            // Jackson exception messages may include request data. Never log them.
            // 解析异常常带请求片段，日志只保留异常类型，不能直接打印异常对象或消息。
            log.warn("Cannot parse LINE webhook, errorType={}", e.getClass().getSimpleName());
            return;
        }
        for (Event event : request.events()) {
            processEvent(event, receivedNanos);
        }
    }

    private void processEvent(Event event, long receivedNanos) {
        if (event == null || event.webhookEventId() == null || event.webhookEventId().isBlank()) {
            log.warn("Skipping LINE event without webhookEventId");
            return;
        }
        String id = event.webhookEventId();
        // Do not retain a short-lived replyToken behind slow/busy workers.
        // Measure from this HTTP delivery, not LINE timestamp (redeliveries retain it).
        // 15 秒是本项目保守的排队预算，不是 LINE 的官方有效期承诺；同批前置业务耗时也计入。
        if (event instanceof MessageEvent && System.nanoTime() - receivedNanos > TimeUnit.SECONDS.toNanos(15)) {
            log.warn("Skipping stale queued reply eventId={}; receipt age exceeds 15 seconds", id);
            return;
        }
        boolean acquired = false;
        try {
            // isRedelivery 只描述此次投递；第一次见到的重投递仍应处理，去重必须看事件 ID。
            boolean redelivery = event.deliveryContext() != null
                    && Boolean.TRUE.equals(event.deliveryContext().isRedelivery());
            if (!deduplicator.tryAcquire(id)) {
                log.info("Skipping duplicate LINE eventId={}, isRedelivery={}", id, redelivery);
                return;
            }
            acquired = true;
            log.info("Processing LINE eventId={}, type={}, isRedelivery={}", id,
                    event.getClass().getSimpleName(), redelivery);
            dispatcher.dispatch(event);
            // Handler 全部成功后才开始保留期，失败任务不能抢先写成“已处理”。
            deduplicator.complete(id);
        } catch (Exception e) {
            // 只释放自己取得的占用；缓存满等抢占前失败不能删除其他任务的记录。
            if (acquired) {
                deduplicator.release(id);
            }
            log.error("LINE event failed, eventId={}, errorType={}; future delivery may retry", id, e.getClass().getSimpleName());
        }
    }
}
