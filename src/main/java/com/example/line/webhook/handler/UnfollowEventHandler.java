package com.example.line.webhook.handler;

import com.linecorp.bot.webhook.model.UnfollowEvent;
import com.example.line.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 接收取消关注事件，由 EventDispatcher 在异步去重后调用，记录 userId 与 eventId 并更新状态。
 * 异步更新用户状态但保留全部数据，不调用 LineMessageService，避免向取消关注用户主动发送。
 */
@Component
public class UnfollowEventHandler implements EventHandler<UnfollowEvent> {
    private static final Logger log = LoggerFactory.getLogger(UnfollowEventHandler.class);
    private final UserService users;

    /**
     * 注入取消关注状态记录入口，不接入自动删除。
     * @param users 用户生命周期记录服务
     */
    public UnfollowEventHandler(UserService users) { this.users = users; }
    /**
     * 声明取消关注事件的策略类型。
     *
     * @return SDK UnfollowEvent 类型
     */
    public Class<UnfollowEvent> eventType() { return UnfollowEvent.class; }
    /**
     * 记录取消关注的用户标识，供接入联调追踪。
     *
     * @param event 非 null 的取消关注事件，source 缺失时允许记录 null 用户标识
     * @throws NullPointerException event 为 null 时抛出；调用方应保证有效事件
     */
    public void handle(UnfollowEvent event) {
        users.recordStatus(event.source() == null ? null : event.source().userId(), false, event.timestamp() == null
                ? java.time.Instant.now() : java.time.Instant.ofEpochMilli(event.timestamp()));
        log.info("LINE unfollow userId={}, eventId={}",
                event.source() == null ? null : event.source().userId(), event.webhookEventId());
    }
}
