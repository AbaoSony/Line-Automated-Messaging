package com.example.line.webhook.handler;

import com.linecorp.bot.webhook.model.FollowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 接收关注事件，由 EventDispatcher 在异步去重后调用，仅记录 userId 与 eventId。
 * 不依赖发送服务，也不自动发送欢迎消息，避免与平台问候消息产生重复体验。
 */
@Component
public class FollowEventHandler implements EventHandler<FollowEvent> {
    private static final Logger log = LoggerFactory.getLogger(FollowEventHandler.class);
    /**
     * 声明关注事件的策略类型。
     *
     * @return SDK FollowEvent 类型
     */
    public Class<FollowEvent> eventType() { return FollowEvent.class; }
    /**
     * 记录关注来源的用户标识，不输出事件原文或 Reply Token。
     *
     * @param event 非 null 的关注事件，source 缺失时允许记录 null 用户标识
     * @throws NullPointerException event 为 null 时抛出；调用方应保证有效事件
     */
    public void handle(FollowEvent event) {
        log.info("LINE follow userId={}, eventId={}",
                event.source() == null ? null : event.source().userId(), event.webhookEventId());
    }
}
