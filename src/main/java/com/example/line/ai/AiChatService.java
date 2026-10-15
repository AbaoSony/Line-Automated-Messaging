package com.example.line.ai;

import com.example.line.ai.coordination.*;
import com.example.line.ai.generation.*;
import com.example.line.ai.memory.ConversationMemory;
import com.example.line.chat.*;
import com.example.line.common.execution.*;
import com.example.line.common.logging.*;
import com.example.line.messaging.LineMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;

/** 编排单实例私聊：有界调度、依赖快照、模型、一次 Reply；仅 LINE 接受后保存正常轮次。 */
@Service
public class AiChatService {
    private final AiProperties properties;
    private final SessionScheduler scheduler;
    private final RedisCoordination coordination;
    private final ConversationMemory memory;
    private final ChatModelClient model;
    private final PersonaLoader persona;
    private final ContextComposer composer;
    private final ChatMessageService chat;
    private final LineMessageService messages;
    private final UserWorkTracker tracker;
    private final DependencyCalls dependencies;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;
    /**
     * 注入会话边界和 P0 记录能力，模型厂商类型不进入编排层。
     * @param properties 有界预算与话术
     * @param scheduler 用户 FIFO
     * @param coordination Redis 锁与限流
     * @param memory 已确认上下文
     * @param model 模型接口
     * @param persona 负责人原始人设
     * @param composer 输入预算裁剪
     * @param chat 异步对话存储
     * @param messages 当前事件 Reply
     * @param tracker 用户删除屏障
     * @param dependencies 有界依赖查询
     * @param meters 指标
     * @param mapper 业务 JSON
     */
    public AiChatService(AiProperties properties,SessionScheduler scheduler,RedisCoordination coordination,
                         ConversationMemory memory,ChatModelClient model,PersonaLoader persona,ContextComposer composer,
                         ChatMessageService chat,LineMessageService messages,UserWorkTracker tracker,
                         DependencyCalls dependencies,MeterRegistry meters,ObjectMapper mapper) {
        this.properties=properties;this.scheduler=scheduler;this.coordination=coordination;this.memory=memory;
        this.model=model;this.persona=persona;this.composer=composer;this.chat=chat;this.messages=messages;
        this.tracker=tracker;this.dependencies=dependencies;this.meters=meters;this.mapper=mapper;
    }
    /**
     * 接收当前私聊并立即返回业务完成阶段，等待任务不占 Webhook 工作线程。
     * @param input 不含凭证的私聊输入
     * @param replyToken 仅当前配置截止预算内使用的短效凭证，不写入模型、数据库或缓存
     * @return 当前处理终态，已成功 Reply 后缓存失败仍正常完成
     */
    public CompletionStage<Void> submit(AiInput input,String replyToken) {
        Deadline deadline=Deadline.current(properties.totalTimeout());
        var lease=tracker.register(input.userId());
        if(lease==null) {skipped("deletion_barrier");return CompletableFuture.completedFuture(null);}
        chat.recordMessage(input.userId(),"INBOUND",input.messageId(),"TEXT",input.content(),
                input.occurredAt(),metadata(input,"LEGACY",null));
        memory.observe(input.userId(),input.messageId(),input.occurredAt(),input.receivedAt(),input.sequence());
        return scheduler.submit(input.userId(),deadline,() -> process(input,replyToken,deadline),() -> {
            meters.counter("ai.queue.rejected").increment();
            BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_QUEUE_REJECTED,"AI session queue rejected",
                    BusinessLog.fields("outcome","SKIPPED","correlation_id",input.correlationId()));
            deliver(input,replyToken,deadline,null,properties.busy(),"BUSY",null,null);
        },lease::close);
    }
    /**
     * 观察非 AI 私聊入站时间，避免其异步落库前被错误视为没有历史。
     * @param input 只含业务字段的私聊输入
     */
    public void observePrivate(AiInput input) {
        memory.observe(input.userId(),input.messageId(),input.occurredAt(),input.receivedAt(),input.sequence());
    }
    private void process(AiInput input,String replyToken,Deadline deadline) {
        String owner=null;
        try {
            if(tracker.blocked(input.userId()) || !deadline.permits(properties.sendReserve())) {skipped("deadline_or_deletion");return;}
            String text,kind;
            ChatResult result=null;
            PrivateHistory snapshot=null;
            try {
                if(!coordination.allow(input.userId())) {
                    meters.counter("ai.rate_limited").increment();
                    BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.USER_RATE_LIMITED,"User rate limited",
                            BusinessLog.fields("outcome","SKIPPED"));
                    throw new ModelFailure("rate_limited");
                }
                owner=coordination.acquire(input.userId(),deadline);
                if(owner==null) {
                    meters.counter("ai.lock.timeout").increment();
                    BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.LOCK_TIMEOUT,"Conversation lock wait expired",
                            BusinessLog.fields("outcome","SKIPPED"));
                    throw new ModelFailure("busy");
                }
                final String holder=owner;
                var readDeadline=new Deadline(deadline.startedNanos(),Math.min(deadline.expiresNanos()-properties.sendReserve().toNanos(),
                        System.nanoTime()+properties.dependencyTimeout().toNanos()),deadline.clock());
                snapshot=dependencies.call(readDeadline,() -> memory.load(input.userId(),input.messageId(),input.receivedAt(),input.sequence(),holder,readDeadline));
                String prompt=persona.render(snapshot.lastInboundAt(),input.occurredAt(),false);
                var history=composer.compose(prompt,input.text(),snapshot.turns());
                if(!deadline.permits(properties.sendReserve()) || tracker.blocked(input.userId())) {skipped("deadline_or_deletion");return;}
                var modelDeadline=new Deadline(deadline.startedNanos(),deadline.expiresNanos()-properties.sendReserve().toNanos(),deadline.clock());
                BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.AI_GENERATE_REQUESTED,"AI generation requested",
                        BusinessLog.fields("correlation_id",input.correlationId(),"message_id",input.messageId(),
                                "content",BusinessLog.fields("system_prompt",prompt,"history",history,"user_message",input.text())));
                long generationStart=System.nanoTime();
                try {result=model.generate(new ChatRequest(prompt,history,input.text(),input.messageId(),input.correlationId(),input.occurredAt(),modelDeadline));}
                finally {meters.timer("ai.generation.duration").record(System.nanoTime()-generationStart,java.util.concurrent.TimeUnit.NANOSECONDS);}
                if(!modelDeadline.permits(Duration.ZERO))throw new ModelFailure("deadline");
                if(result==null)throw new ModelFailure("invalid_response");
                String invalid=invalid(result);
                var fields=BusinessLog.fields("correlation_id",input.correlationId(),"model",result.modelName(),
                        "input_tokens",result.inputTokens(),"output_tokens",result.outputTokens(),"duration_ms",result.latencyMs(),
                        "content",BusinessLog.fields("text",result.text(),"status",result.status(),
                                "incomplete_reason",result.incompleteReason(),"reasoning_tokens",result.reasoningTokens()),"outcome",invalid==null?"SUCCESS":"FAILED");
                if(result.inputTokens()!=null)meters.counter("ai.tokens","direction","input").increment(result.inputTokens());
                if(result.outputTokens()!=null)meters.counter("ai.tokens","direction","output").increment(result.outputTokens());
                if(invalid==null) {
                    BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.AI_GENERATE_SUCCESS,"AI generation completed",fields);
                    text=result.text();kind="AI";
                } else {
                    fields.put("error_type",invalid);meters.counter("ai.generation.failed","reason",invalid).increment();
                    BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_GENERATE_FAILED,"AI generation invalid",fields);
                    text=properties.fallback();kind="FALLBACK";
                }
            } catch(ModelFailure failure) {
                kind=switch(failure.kind()){case "input_too_long"->"INPUT_TOO_LONG";case "rate_limited"->"RATE_LIMITED";case "busy"->"BUSY";default->"FALLBACK";};
                text=switch(kind){case "INPUT_TOO_LONG"->properties.inputTooLong();case "RATE_LIMITED"->properties.rateLimited();case "BUSY"->properties.busy();default->properties.fallback();};
                if(kind.equals("FALLBACK")) {
                    meters.counter("ai.generation.failed","reason",failure.kind()).increment();
                    BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_GENERATE_FAILED,"AI generation failed",
                            BusinessLog.fields("error_type",failure.kind(),"duration_ms",deadline.elapsedMillis(),"outcome","FAILED"));
                }
            } catch(RuntimeException failure) {
                dependencyFailure("read_or_lock",failure);
                kind="SERVICE_UNAVAILABLE";text=properties.unavailable();
            }
            if(kind.equals("FALLBACK")) {
                meters.counter("ai.fallback.attempted").increment();
                BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_FALLBACK_USED,"AI fallback attempted",
                        BusinessLog.fields("reply_kind",kind,"content",Map.of("text",text),"outcome","SKIPPED"));
            }
            // 发送阶段位于生成兜底 catch 之外；Reply 结果未知后绝不能再次使用同一 Token。
            deliver(input,replyToken,deadline,owner,text,kind,result,snapshot);
        } finally {
            meters.timer("ai.logical.duration").record(deadline.elapsedMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
            if(owner!=null) try {coordination.release(input.userId(),owner);} catch(RuntimeException failure){dependencyFailure("lock_release",failure);}
        }
    }
    private void deliver(AiInput input,String replyToken,Deadline deadline,String owner,String text,String kind,ChatResult result,PrivateHistory snapshot) {
        if(tracker.blocked(input.userId()) || !deadline.permits(Duration.ZERO)) {skipped("deadline_or_deletion");return;}
        if(owner!=null) {
            try {if(!coordination.owns(input.userId(),owner)){skipped("lock_lost");return;}}
            catch(RuntimeException failure){dependencyFailure("ownership",failure);skipped("ownership_unknown");return;}
        }
        if(!deadline.permits(Duration.ZERO)){skipped("deadline");return;}
        messages.replyText(replyToken,text,deadline);
        chat.recordMessage(input.userId(),"OUTBOUND",null,"TEXT",mapper.createObjectNode().put("text",text),Instant.now(),
                metadata(input,kind,kind.equals("AI")?result:null));
        meters.counter("ai.reply.accepted","kind",kind).increment();
        if(kind.equals("FALLBACK"))meters.counter("ai.fallback.accepted").increment();
        if(kind.equals("AI") && snapshot!=null && !tracker.blocked(input.userId()) && deadline.permits(Duration.ZERO)) {
            var turns=new ArrayList<>(snapshot.turns());
            turns.removeIf(turn -> turn.messageId().equals(input.messageId()));
            turns.add(new PrivateHistory.Turn(input.messageId(),input.correlationId(),input.text(),text,input.occurredAt(),input.receivedAt(),input.sequence()));
            try {memory.save(input.userId(),owner,new PrivateHistory(java.util.List.copyOf(turns),input.occurredAt()));}
            catch(RuntimeException failure){dependencyFailure("cache_write_after_reply",failure);}
        }
    }
    private ChatMetadata metadata(AiInput input,String kind,ChatResult result) {
        return new ChatMetadata("USER",input.userId(),kind,input.messageId(),input.correlationId(),input.receivedAt(),input.sequence(),
                result==null?null:result.modelName(),result==null?null:result.inputTokens(),result==null?null:result.outputTokens(),result==null?null:result.latencyMs());
    }
    private String invalid(ChatResult result) {
        if(!"COMPLETED".equals(result.status()))return "incomplete";
        if(result.text()==null || result.text().isBlank())return "empty_output";
        if(result.text().length()>5000)return "output_too_long";
        return null;
    }
    private void dependencyFailure(String stage,RuntimeException failure) {
        meters.counter("ai.dependency.failed","stage",stage).increment();
        BusinessLog.failure(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_DEPENDENCY_FAILED,"AI dependency unavailable",failure,
                BusinessLog.fields("stage",stage,"outcome","FAILED"));
    }
    private void skipped(String reason) {
        meters.counter("ai.reply.skipped","reason",reason).increment();
        BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_REPLY_SKIPPED,"AI reply skipped",
                BusinessLog.fields("reason",reason,"outcome","SKIPPED"));
    }
}
