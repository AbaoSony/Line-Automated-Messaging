package com.example.line.webhook.handler;

import com.example.line.messaging.LineMessageService;
import com.example.line.chat.ChatMessageService;
import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogData;
import com.example.line.common.logging.LogEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.EventMode;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

/**
 * 处理 SDK MessageEvent，是 EventDispatcher 与 LineMessageService 之间的消息业务策略。
 * 文本回显原文，其他可解析消息统一回复“暂不支持”；异步记录入站与成功回复，standby 只记录入站。
 */
@Component
public class MessageEventHandler implements EventHandler<MessageEvent> {
    private static final Logger log = LoggerFactory.getLogger(MessageEventHandler.class);
    private final LineMessageService messages;
    private final ChatMessageService chat;
    private final ObjectMapper mapper = ModelObjectMapper.createNewObjectMapper();

    /**
     * 注入统一发送服务，避免在 Handler 中重复实现 SDK 错误分类或消息校验。
     *
     * @param messages 即时 Reply 服务；不得在此保存 Reply Token 供延迟任务使用
     * @param chat 独立异步对话记录入口，不等待落库完成
     */
    public MessageEventHandler(LineMessageService messages, ChatMessageService chat) {
        this.messages = messages;
        this.chat = chat;
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
     * @throws com.example.line.messaging.LineSendException SDK Reply 失败、超时或被中断时抛出
     * @throws Exception 遵循统一 Handler 契约，将处理失败交给上层异步接入服务
     */
    @Override
    public void handle(MessageEvent event) throws Exception {
        if (event.message() == null) {
            throw new IllegalArgumentException("Missing message content");
        }
        String userId = event.source() == null ? null : event.source().userId();
        JsonNode content = mapper.valueToTree(event.message());
        String messageType = content.path("type").asText("unknown").toUpperCase(java.util.Locale.ROOT);
        BusinessLog.write(log, Level.INFO, LogEvents.USER_MESSAGE_RECEIVED, "LINE user message received",
                BusinessLog.fields("user_id", userId, "webhook_event_id", event.webhookEventId(),
                        "message_type", messageType, "message_id", event.message().id(),
                        "text_length", event.message() instanceof TextMessageContent text && text.text() != null ? text.text().length() : null,
                        "content", LogData.business(event.message()), "outcome", "SUCCESS"));
        chat.recordInbound(userId, event.message().id(), messageType, content, event.timestamp() == null
                ? java.time.Instant.now() : java.time.Instant.ofEpochMilli(event.timestamp()));
        // standby 是 LINE 的非活动 Bot 模式，不能因为有消息内容就直接回复。
        if (event.mode() == EventMode.STANDBY) {
            BusinessLog.write(log, Level.INFO, LogEvents.WEBHOOK_EVENT_SKIPPED, "Skipping reply for standby",
                    BusinessLog.fields("outcome", "SKIPPED", "reason", "standby"));
            return;
        }
        if (event.message() instanceof TextMessageContent text) {
            messages.replyText(event.replyToken(), text.text());
            chat.recordOutbound(userId, "TEXT", mapper.createObjectNode().put("text", text.text()));
        } else {
            // 仅持久化业务元数据，不自动下载二进制；原事件中的 Reply Token 不得进入落库任务。
            BusinessLog.write(log, Level.INFO, LogEvents.WEBHOOK_EVENT_SKIPPED, "Unsupported LINE message type",
                    BusinessLog.fields("message_type", messageType, "message_id", event.message().id(),
                            "outcome", "SKIPPED", "reason", "unsupported_message"));
            messages.replyText(event.replyToken(), "暂不支持");
            chat.recordOutbound(userId, "TEXT", mapper.createObjectNode().put("text", "暂不支持"));
        }
    }
}
