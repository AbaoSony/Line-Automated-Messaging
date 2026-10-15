package com.example.line.webhook.handler;

import com.linecorp.bot.webhook.model.FollowEvent;
import com.example.line.user.UserService;
import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

/**
 * 接收关注事件，由 EventDispatcher 在异步去重后调用，记录 userId 与 eventId 并异步更新用户状态。
 * 不依赖发送服务，也不自动发送欢迎消息，避免与平台问候消息产生重复体验。
 */
@Component
public class FollowEventHandler implements EventHandler<FollowEvent> {
    private static final Logger log = LoggerFactory.getLogger(FollowEventHandler.class);
    private final UserService users;

    /**
     * 注入独立异步用户状态入口。
     * @param users 用户生命周期记录服务
     */
    public FollowEventHandler(UserService users) { this.users = users; }
    /**
     * 声明关注事件的策略类型。
     *
     * @return SDK FollowEvent 类型
     */
    public Class<FollowEvent> eventType() { return FollowEvent.class; }
    /**
     * 记录关注来源的完整用户标识并提交状态更新，不保存 Reply Token。
     *
     * @param event 非 null 的关注事件，source 缺失时允许记录 null 用户标识
     * @throws NullPointerException event 为 null 时抛出；调用方应保证有效事件
     */
    public void handle(FollowEvent event) {
        users.recordStatus(event.source() == null ? null : event.source().userId(), true, event.timestamp() == null
                ? java.time.Instant.now() : java.time.Instant.ofEpochMilli(event.timestamp()));
        BusinessLog.write(log, Level.INFO, LogEvents.USER_FOLLOW, "LINE follow userId={}, eventId={}",
                BusinessLog.fields("user_id", event.source() == null ? null : event.source().userId(),
                        "webhook_event_id", event.webhookEventId(), "outcome", "SUCCESS"),
                event.source() == null ? null : event.source().userId(), event.webhookEventId());
    }
}
