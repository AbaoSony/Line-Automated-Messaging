package com.example.line.handler;

import com.example.line.service.LineMessageService;
import com.linecorp.bot.webhook.model.EventMode;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 处理 SDK MessageEvent，是 EventDispatcher 与 LineMessageService 之间的消息业务策略。
 * 文本回显原文，其他可解析消息统一回复“暂不支持”；standby 模式不发送，避免非活动 Bot 回复。
 */
@Component
public class MessageEventHandler implements EventHandler<MessageEvent> {
    private static final Logger log = LoggerFactory.getLogger(MessageEventHandler.class);
    private final LineMessageService messages;

    /**
     * 注入统一发送服务，避免在 Handler 中重复实现 SDK 错误分类或消息校验。
     *
     * @param messages 即时 Reply 服务；不得在此保存 Reply Token 供延迟任务使用
     */
    public MessageEventHandler(LineMessageService messages) {
        this.messages = messages;
    }

    /**
     * 向注册分发器声明消息事件策略。
     *
     * @return SDK MessageEvent 类型
     */
    @Override
    public Class<MessageEvent> eventType() {
        return MessageEvent.class;
    }

    /**
     * 处理消息并即时回复；仅使用当前事件的短效、一次性 Reply Token。
     *
     * @param event 非 null 的消息事件，message 必须存在；standby 模式只记录并跳过发送
     * @throws IllegalArgumentException 消息内容缺失，或发送参数不满足约束时抛出
     * @throws com.example.line.service.LineSendException SDK Reply 失败、超时或被中断时抛出
     * @throws Exception 遵循统一 Handler 契约，将处理失败交给上层异步接入服务
     */
    @Override
    public void handle(MessageEvent event) throws Exception {
        if (event.message() == null) {
            throw new IllegalArgumentException("Missing message content");
        }
        // standby 是 LINE 的非活动 Bot 模式，不能因为有消息内容就直接回复。
        if (event.mode() == EventMode.STANDBY) {
            log.info("Skipping reply for standby eventId={}", event.webhookEventId());
            return;
        }
        if (event.message() instanceof TextMessageContent text) {
            messages.replyText(event.replyToken(), text.text());
        } else {
            // Includes image, sticker, location, file, audio and video. Do not log message contents or reply tokens.
            // 只记录类型和追踪 ID，避免图片元数据、位置、文件名或 Reply Token 随原始事件进入日志。
            log.info("Unsupported LINE message type={}, messageId={}, eventId={}",
                    event.message().getClass().getSimpleName(), event.message().id(), event.webhookEventId());
            messages.replyText(event.replyToken(), "暂不支持");
        }
    }
}
