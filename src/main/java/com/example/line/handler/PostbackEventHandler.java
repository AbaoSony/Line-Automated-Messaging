package com.example.line.handler;

import com.linecorp.bot.webhook.model.PostbackEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 作为 Postback 的独立业务入口，由 EventDispatcher 选择调用，当前只记录事件追踪信息。
 * 尚未解析订单动作或调用发送服务；后续业务应在该策略扩展而非改动 Controller。
 */
@Component
public class PostbackEventHandler implements EventHandler<PostbackEvent> {
    private static final Logger log = LoggerFactory.getLogger(PostbackEventHandler.class);
    /**
     * 声明回传事件的策略类型。
     *
     * @return SDK PostbackEvent 类型
     */
    public Class<PostbackEvent> eventType() { return PostbackEvent.class; }
    /**
     * 接收 Postback 并记录 eventId，不把可能包含业务或用户信息的 data 写入日志。
     *
     * @param event 非 null 的 Postback 事件；当前不要求 postback 内容参与业务校验
     * @throws NullPointerException event 为 null 时抛出；调用方应保证有效事件
     */
    public void handle(PostbackEvent event) {
        // Future business logic can read event.postback().data() here.
        // 这里保留业务扩展入口，当前日志不等于已经执行 data 中描述的动作。
        log.info("LINE postback received eventId={}", event.webhookEventId());
    }
}
