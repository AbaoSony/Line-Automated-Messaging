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
    public void handle(FollowEvent event) { handleAsync(event); }

    /**
     * 在有界异步生命周期入口比较原始排序，仅有效状态变更触发业务通知；不保存 SDK 事件。
     * @param event 当前follow事件
     * @return 状态处理结束，失败已安全记录，不要求 Webhook 重试
     */
    @Override public java.util.concurrent.CompletionStage<Void> handleAsync(FollowEvent event) {
        String user=event.source()==null?null:event.source().userId();
        String id=event.webhookEventId();
        var at=event.timestamp()==null?java.time.Instant.now():java.time.Instant.ofEpochMilli(event.timestamp());
        boolean redelivery=event.deliveryContext()!=null && Boolean.TRUE.equals(event.deliveryContext().isRedelivery());
        var order=com.example.line.user.LifecycleOrder.current(at,id,redelivery);
        return users.recordStatus(user,true,order).thenAccept(applied ->
            BusinessLog.write(log,Level.INFO,LogEvents.USER_FOLLOW,"LINE follow userId={}, eventId={}",
                BusinessLog.fields("user_id",user,"webhook_event_id",id,"outcome",applied?"SUCCESS":"SKIPPED"),user,id));
    }
}
