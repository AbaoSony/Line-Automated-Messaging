package com.example.line.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private final ObjectMapper mapper = ModelObjectMapper.createNewObjectMapper();
    private final Executor executor;
    private final EventDispatcher dispatcher;
    private final EventDeduplicator deduplicator;

    public WebhookService(@Qualifier("webhookExecutor") Executor executor,
            EventDispatcher dispatcher, EventDeduplicator deduplicator) {
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.deduplicator = deduplicator;
    }

    public void submit(byte[] body) {
        try {
            executor.execute(() -> process(body));
        } catch (RejectedExecutionException e) {
            log.error("LINE webhook not queued; returning 200 per acknowledgement policy");
        }
    }

    void process(byte[] body) {
        final CallbackRequest request;
        try {
            request = mapper.readValue(body, CallbackRequest.class);
            if (request == null || request.events() == null) {
                log.warn("LINE webhook has no events array");
                return;
            }
        } catch (Exception e) {
            // Jackson exception messages may include request data. Never log them.
            log.warn("Cannot parse LINE webhook, errorType={}", e.getClass().getSimpleName());
            return;
        }
        for (Event event : request.events()) {
            processEvent(event);
        }
    }

    private void processEvent(Event event) {
        if (event == null || event.webhookEventId() == null || event.webhookEventId().isBlank()) {
            log.warn("Skipping LINE event without webhookEventId");
            return;
        }
        String id = event.webhookEventId();
        boolean acquired = false;
        try {
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
            deduplicator.complete(id);
        } catch (Exception e) {
            if (acquired) {
                deduplicator.release(id);
            }
            log.error("LINE event failed, eventId={}, errorType={}; future delivery may retry", id, e.getClass().getSimpleName());
        }
    }
}
