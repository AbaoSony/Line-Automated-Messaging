package com.example.line.ai;

import com.example.line.ai.aggregation.*;
import com.example.line.ai.coordination.*;
import com.example.line.ai.decision.*;
import com.example.line.ai.generation.*;
import com.example.line.ai.memory.ConversationMemory;
import com.example.line.chat.*;
import com.example.line.common.execution.*;
import com.example.line.common.logging.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;

/** P2 实际私聊入口：逐条记录及限流，唯一轮次协调器收集，规则决定时机，正文完整保留成员边界。 */
@Service
public class AggregatedChatFlow {
    private static final int MAX_REPLY_CHARACTERS = 5000;
    private final AiProperties ai;
    private final TurnFlowProperties mode;
    private final AggregationProperties aggregation;
    private final DecisionProperties decision;
    private final ModelProperties modelProperties;
    private final SessionScheduler scheduler;
    private final RedisCoordination coordination;
    private final ConversationMemory memory;
    private final ChatModelClient model;
    private final PersonaLoader persona;
    private final ContextComposer composer;
    private final ChatMessageService chat;
    private final UserWorkTracker tracker;
    private final DependencyCalls dependencies;
    private final RuleTimingPolicy rules;
    private final TurnReplyService replies;
    private final MeterRegistry meters;
    private TurnCoordinator turns;
    /**
     * 装配现有 P1 能力，不创建第二个会话所有者或额外模型线程池。
     * @param ai 原始入口及发送预算
     * @param mode 显式流程选择
     * @param aggregation 聚合容量与截止
     * @param decision 时机策略配置
     * @param modelProperties 正文配置，保留用户选择的模型与推理
     * @param scheduler 共享调度与删除入口
     * @param coordination 原有 Redis 限流和锁
     * @param memory 已接受的完整历史
     * @param model 正文模型接口
     * @param persona 用户人设
     * @param composer 完整轮输入预算
     * @param chat 原文异步存储
     * @param tracker 删除工作登记
     * @param dependencies 有界依赖调用
     * @param rules 规则时机建议
     * @param replies 一次发送与结果记录
     * @param meters 无用户标签指标
     */
    public AggregatedChatFlow(AiProperties ai,TurnFlowProperties mode,AggregationProperties aggregation,DecisionProperties decision,
                              ModelProperties modelProperties,SessionScheduler scheduler,RedisCoordination coordination,
                              ConversationMemory memory,ChatModelClient model,PersonaLoader persona,ContextComposer composer,
                              ChatMessageService chat,UserWorkTracker tracker,DependencyCalls dependencies,RuleTimingPolicy rules,
                              TurnReplyService replies,MeterRegistry meters) {
        this.ai=ai;this.mode=mode;this.aggregation=aggregation;this.decision=decision;this.modelProperties=modelProperties;
        this.scheduler=scheduler;this.coordination=coordination;this.memory=memory;this.model=model;this.persona=persona;
        this.composer=composer;this.chat=chat;this.tracker=tracker;this.dependencies=dependencies;this.rules=rules;this.replies=replies;this.meters=meters;
    }
    /**
     * 启动时互斥切换到轮次流程；尚未实现的 MODEL 模式明确失败，不能悄悄冒充模型判断。
     * @throws IllegalArgumentException 当前阶段选择 MODEL 时抛出
     */
    @PostConstruct public void initialize() {
        if (!mode.enabled()) return;
        if (decision.mode()!=DecisionProperties.Mode.RULE) throw new IllegalArgumentException("P2 MODEL decision is not implemented; use AI_DECISION_MODE=RULE");
        turns=scheduler.enableTurns(aggregation,decision,modelProperties.totalTimeout(),this::execute);
    }
    /**
     * 有序入口逐条限流与保留原文，正常终态完成所有成员的 Webhook 去重占用。
     * @param input 不含凭证的完整入站
     * @param token 当前即时流程凭证，原子准入后仅协调器持有
     * @return 整轮实际工作及清理退出后的正常完成阶段
     */
    public CompletionStage<Void> submit(AiInput input,String token) {
        Deadline deadline=Deadline.current(ai.totalTimeout());
        var lease=tracker.register(input.userId());
        if (lease==null) return CompletableFuture.completedStage(null);
        InboundMember member;
        try { member=new InboundMember(input.userId(),input.correlationId(),input.messageId(),input.text(),input.occurredAt(),
                input.receivedAt(),input.sequence(),Optional.ofNullable(MDC.get("trace_id")).orElseGet(() -> UUID.randomUUID().toString()),input.content()); }
        catch (IllegalArgumentException | NullPointerException failure) {
            lease.close(); dependencyFailure("invalid_input",failure); return CompletableFuture.completedStage(null);
        }
        try {
            chat.recordMessage(input.userId(),"INBOUND",input.messageId(),"TEXT",input.content(),input.occurredAt(),
                    new ChatMetadata("USER",input.userId(),"LEGACY",input.messageId(),input.correlationId(),input.receivedAt(),input.sequence(),null,null,null,null));
            memory.observe(input.userId(),input.messageId(),input.occurredAt(),input.receivedAt(),input.sequence());
            if (!coordination.allow(input.userId())) {
                meters.counter("ai.rate_limited").increment();
                log(LogEvents.USER_RATE_LIMITED,"User rate limited",BusinessLog.fields("outcome","SKIPPED"));
                return rejected(member,token,deadline,"RATE_LIMITED",lease);
            }
            var admission=turns.admit(member,deadline,token);
            log(LogEvents.AI_TURN_ADMITTED,"AI turn admission",BusinessLog.fields("turn_id",admission.turnId(),"message_id",member.messageId(),
                    "outcome",admission.admitted()?"SUCCESS":"SKIPPED"));
            if (!admission.admitted()) {
                meters.counter("ai.queue.rejected").increment();
                var reason=admission.completion().toCompletableFuture().join().reason();
                log(LogEvents.AI_QUEUE_REJECTED,"AI turn rejected",BusinessLog.fields("reason",reason,"outcome","SKIPPED"));
                return rejected(member,token,deadline,reason==TurnResult.Reason.INPUT_TOO_LONG?"INPUT_TOO_LONG":"BUSY",lease);
            }
            var trace=MDC.getCopyOfContextMap();
            return admission.completion().thenApply(result -> {
                var previous=MDC.getCopyOfContextMap();
                try {
                    if(trace==null) MDC.clear(); else MDC.setContextMap(trace);
                    log(LogEvents.AI_TURN_COMPLETED,"AI turn completed",BusinessLog.fields("turn_id",admission.turnId(),
                            "message_id",member.messageId(),"reason",result.reason(),"content",BusinessLog.fields("state",result.state()),
                            "outcome",result.state()==TurnState.ACCEPTED?"SUCCESS":result.state()==TurnState.FAILED || result.state()==TurnState.UNKNOWN?"FAILED":"SKIPPED"));
                    return (Void)null;
                }
                finally { if(previous==null) MDC.clear(); else MDC.setContextMap(previous); lease.close(); }
            });
        } catch (RuntimeException failure) {
            dependencyFailure("admission",failure);
            return rejected(member,token,deadline,"SERVICE_UNAVAILABLE",lease);
        }
    }
    private CompletionStage<Void> rejected(InboundMember member,String token,Deadline deadline,String kind,UserWorkTracker.Lease lease) {
        return scheduler.rejectHint(() -> replies.rejected(member,token,deadline,kind),lease::close);
    }
    private void execute(TurnTicket ticket,TurnSnapshot snapshot,Deadline deadline) {
        if (ticket.kind()!=TurnTicket.Kind.GENERATION) {
            var advice=rules.decide(snapshot.members(),aggregation.ruleWait());
            boolean applied=turns.completeDecision(ticket,advice);
            log(LogEvents.AI_TURN_DECIDED,"AI rule timing decision",BusinessLog.fields("turn_id",ticket.turnId(),"member_count",snapshot.members().size(),
                    "input_version",ticket.inputVersion(),"reason",advice.reason(),"content",BusinessLog.fields("action",advice.action(),"wait_ms",advice.waitFor().toMillis()),
                    "outcome",applied?"SUCCESS":"SKIPPED"));
            return;
        }
        String owner=null;
        try {
            Deadline total=turns.totalDeadline(ticket);
            if (total==null || tracker.blocked(ticket.userId())) return;
            owner=coordination.acquire(ticket.userId(),total);
            if (owner==null) {
                meters.counter("ai.lock.timeout").increment();
                log(LogEvents.LOCK_TIMEOUT,"Conversation lock wait expired",BusinessLog.fields("outcome","SKIPPED"));
                replies.send(turns,ticket,snapshot.members(),null,replies.text("BUSY"),"BUSY",null,null); return;
            }
            final String holder=owner;
            var first=snapshot.members().get(0);
            PrivateHistory history;
            ChatResult result=null; String kind="AI", text;
            try {
                history=dependencies.call(deadline,() -> memory.load(first.userId(),first.messageId(),first.receivedAt(),first.sequence(),holder,deadline));
                String prompt=persona.render(history.lastInboundAt(),first.occurredAt(),false);
                var current=snapshot.members().stream().map(InboundMember::text).toList();
                var old=composer.composeMembers(prompt,current,history.turns());
                if (turns.totalDeadline(ticket)==null || tracker.blocked(ticket.userId())) return;
                var content=BusinessLog.fields("system_prompt",prompt,"history",old,"user_message",first.text());
                if(current.size()>1)content.put("current_messages",snapshot.members());
                log(LogEvents.AI_GENERATE_REQUESTED,"AI generation requested",BusinessLog.fields("turn_id",ticket.turnId(),"member_count",current.size(),
                        "input_version",ticket.inputVersion(),"generation_version",ticket.generationVersion(),"correlation_id",first.eventId(),"message_id",first.messageId(),"content",content));
                long started=System.nanoTime();
                try { result=model.generate(new ChatRequest(prompt,old,first.text(),first.messageId(),first.eventId(),first.occurredAt(),deadline,
                        current,() -> turns.reserveGenerationRetry(ticket))); }
                finally { meters.timer("ai.generation.duration").record(System.nanoTime()-started,TimeUnit.NANOSECONDS); }
                if(!deadline.permits(Duration.ZERO)) throw new ModelFailure("deadline");
                String invalid=result==null?"invalid_response":invalid(result);
                if(result!=null) generated(ticket,first.eventId(),result,invalid);
                if(invalid!=null) throw new ModelFailure(invalid);
                text=result.text();
            } catch(ModelFailure failure) {
                history=null; kind=failure.kind().equals("input_too_long")?"INPUT_TOO_LONG":"FALLBACK";text=replies.text(kind);
                if(result==null && kind.equals("FALLBACK")) {
                    meters.counter("ai.generation.failed","reason",failure.kind()).increment();
                    log(LogEvents.AI_GENERATE_FAILED,"AI generation failed",BusinessLog.fields("error_type",failure.kind(),"duration_ms",deadline.elapsedMillis(),"outcome","FAILED"));
                }
            } catch(RuntimeException failure) {
                dependencyFailure("read",failure);history=null;kind="SERVICE_UNAVAILABLE";text=replies.text(kind);
            }
            if (tracker.blocked(ticket.userId()) || turns.totalDeadline(ticket)==null) return;
            if(!coordination.owns(ticket.userId(),owner)) return;
            replies.send(turns,ticket,snapshot.members(),owner,text,kind,result,history);
        } catch(RuntimeException failure) { dependencyFailure("lock_or_ownership",failure); }
        finally {
            meters.timer("ai.logical.duration").record(deadline.elapsedMillis(),TimeUnit.MILLISECONDS);
            if(owner!=null) try { coordination.release(ticket.userId(),owner); }
            catch(RuntimeException failure) { dependencyFailure("lock_release",failure); }
        }
    }
    private String invalid(ChatResult result) {
        if(!"COMPLETED".equals(result.status()))return "incomplete";
        if(result.text()==null || result.text().isBlank())return "empty_output";
        return result.text().length()>MAX_REPLY_CHARACTERS?"output_too_long":null;
    }
    private void generated(TurnTicket ticket,String correlationId,ChatResult result,String invalid) {
        if(result.inputTokens()!=null)meters.counter("ai.tokens","direction","input").increment(result.inputTokens());
        if(result.outputTokens()!=null)meters.counter("ai.tokens","direction","output").increment(result.outputTokens());
        var fields=BusinessLog.fields("turn_id",ticket.turnId(),"correlation_id",correlationId,"model",result.modelName(),"input_tokens",result.inputTokens(),
                "output_tokens",result.outputTokens(),"duration_ms",result.latencyMs(),"content",BusinessLog.fields("text",result.text(),"status",result.status(),
                        "incomplete_reason",result.incompleteReason(),"reasoning_tokens",result.reasoningTokens()),"outcome",invalid==null?"SUCCESS":"FAILED");
        if(invalid!=null) { fields.put("error_type",invalid);meters.counter("ai.generation.failed","reason",invalid).increment(); }
        log(invalid==null?LogEvents.AI_GENERATE_SUCCESS:LogEvents.AI_GENERATE_FAILED,"AI generation result",fields);
    }
    private void dependencyFailure(String stage,RuntimeException failure) {
        meters.counter("ai.dependency.failed","stage",stage).increment();
        BusinessLog.failure(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_DEPENDENCY_FAILED,"AI dependency unavailable",failure,
                BusinessLog.fields("stage",stage,"outcome","FAILED"));
    }
    private void log(String event,String message,Map<String,Object> fields) {
        Level level=Set.of(LogEvents.AI_GENERATE_FAILED,LogEvents.AI_QUEUE_REJECTED,LogEvents.LOCK_TIMEOUT).contains(event)?Level.WARN:Level.INFO;
        BusinessLog.write(LoggerFactory.getLogger(getClass()),level,event,message,fields);
    }
}
