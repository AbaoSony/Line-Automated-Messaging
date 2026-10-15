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
 * 活动私聊文本交给 AI，群聊与聊天室文本回显；其他消息回复“暂不支持”，standby 只记录入站。
 */
@Component
public class MessageEventHandler implements EventHandler<MessageEvent> {
    private static final Logger log = LoggerFactory.getLogger(MessageEventHandler.class);
    private final LineMessageService messages;
    private final ChatMessageService chat;
    private final com.example.line.ai.AiChatService ai;
    private final java.util.concurrent.Executor executor;
    private final ObjectMapper mapper = ModelObjectMapper.createNewObjectMapper();

    /**
     * 注入统一发送服务，避免在 Handler 中重复实现 SDK 错误分类或消息校验。
     *
     * @param messages 即时 Reply 服务；不得在此保存 Reply Token 供延迟任务使用
     * @param chat 独立异步对话记录入口，不等待落库完成
     * @param ai 活动私聊编排
     * @param executor 非 AI 消息有界执行器
     */
    public MessageEventHandler(LineMessageService messages, ChatMessageService chat,com.example.line.ai.AiChatService ai,
                               @org.springframework.beans.factory.annotation.Qualifier("webhookExecutor") java.util.concurrent.Executor executor) {
        this.messages = messages;
        this.chat = chat;
        this.ai=ai;this.executor=executor;
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
        try {handleAsync(event).toCompletableFuture().get();}
        catch(java.util.concurrent.ExecutionException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw new IllegalStateException("Event failed");}
    }

    /**
     * 有序入口仅提交短时 AI 会话；非 AI 业务在原 Webhook 执行器中处理，避免阻塞后续用户入队。
     * @param event 当前消息事件
     * @return 真实处理终态
     * @throws IllegalArgumentException 消息内容缺失时抛出
     */
    @Override
    public java.util.concurrent.CompletionStage<Void> handleAsync(MessageEvent event) {
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
        JsonNode source=event.source()==null?mapper.createObjectNode():mapper.valueToTree(event.source());
        String sourceType=source.path("type").asText("UNKNOWN").toUpperCase(java.util.Locale.ROOT);
        var occurred=event.timestamp()==null?java.time.Instant.now():java.time.Instant.ofEpochMilli(event.timestamp());
        String accepted=org.slf4j.MDC.get("accepted_at");
        var received=(accepted==null?java.time.Instant.now():java.time.Instant.parse(accepted)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String seq=org.slf4j.MDC.get("received_sequence");long sequence=seq==null?0:Long.parseLong(seq);
        var input=new com.example.line.ai.AiInput(userId,event.message().id(),event.message() instanceof TextMessageContent text?text.text():null,
                mapper.valueToTree(LogData.business(event.message())),event.webhookEventId(),occurred,received,sequence);
        if(sourceType.equals("USER") && userId!=null && !userId.isBlank() && event.mode()==EventMode.ACTIVE
                && event.message() instanceof TextMessageContent) return ai.submit(input,event.replyToken());
        if(sourceType.equals("USER") && userId!=null && !userId.isBlank())ai.observePrivate(input);
        String conversation=sourceType.equals("USER")?userId:sourceType.equals("GROUP")?source.path("groupId").asText():source.path("roomId").asText(null);
        var metadata=new com.example.line.chat.ChatMetadata(sourceType,conversation,"LEGACY",event.message().id(),event.webhookEventId(),received,sequence,null,null,null,null);
        chat.recordMessage(userId,"INBOUND",event.message().id(),messageType,content,occurred,metadata);
        var result=new java.util.concurrent.CompletableFuture<Void>();
        Runnable task=new com.example.line.common.logging.MdcTaskDecorator().decorate(() -> {
            try {legacy(event,userId,metadata);result.complete(null);}catch(RuntimeException failure){result.completeExceptionally(failure);}
        });
        try {executor.execute(task);}catch(java.util.concurrent.RejectedExecutionException failure){result.completeExceptionally(failure);}
        return result;
    }

    private void legacy(MessageEvent event,String userId,com.example.line.chat.ChatMetadata metadata) {
        // standby 是 LINE 的非活动 Bot 模式，不能因为有消息内容就直接回复。
        if (event.mode() == EventMode.STANDBY) {
            BusinessLog.write(log, Level.INFO, LogEvents.WEBHOOK_EVENT_SKIPPED, "Skipping reply for standby",
                    BusinessLog.fields("outcome", "SKIPPED", "reason", "standby"));
            return;
        }
        if (event.message() instanceof TextMessageContent text) {
            messages.replyText(event.replyToken(), text.text());
            chat.recordMessage(userId,"OUTBOUND",null,"TEXT",mapper.createObjectNode().put("text",text.text()),java.time.Instant.now(),metadata);
        } else {
            // 仅持久化业务元数据，不自动下载二进制；原事件中的 Reply Token 不得进入落库任务。
            BusinessLog.write(log, Level.INFO, LogEvents.WEBHOOK_EVENT_SKIPPED, "Unsupported LINE message type",
                    BusinessLog.fields("message_type", mapper.valueToTree(event.message()).path("type").asText().toUpperCase(java.util.Locale.ROOT), "message_id", event.message().id(),
                            "outcome", "SKIPPED", "reason", "unsupported_message"));
            messages.replyText(event.replyToken(), "暂不支持");
            chat.recordMessage(userId,"OUTBOUND",null,"TEXT",mapper.createObjectNode().put("text","暂不支持"),java.time.Instant.now(),metadata);
        }
    }
}
