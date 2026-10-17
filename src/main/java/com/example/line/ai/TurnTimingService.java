package com.example.line.ai;

import com.example.line.ai.aggregation.*;
import com.example.line.ai.coordination.*;
import com.example.line.ai.decision.*;
import com.example.line.ai.memory.ConversationMemory;
import com.example.line.common.execution.*;
import com.example.line.common.logging.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;

/** 在共享轮次执行器内加载已确认历史并运行时机模型；不持有会话映射，失败交唯一协调器以最新快照回退。 */
@Service
public class TurnTimingService {
    private final TimingModelClient model;
    private final DecisionCircuit circuit;
    private final RedisCoordination coordination;
    private final ConversationMemory memory;
    private final DependencyCalls dependencies;
    private final UserWorkTracker tracker;
    private final AiProperties ai;
    private final AggregationProperties aggregation;
    private final MeterRegistry meters;
    /**
     * 复用原有有界依赖、所有权和工作登记，不新增用户调度器或策略线程池。
     * @param model 厂商无关时机接口
     * @param circuit 全局常量空间熔断
     * @param coordination 当前 Redis 所有权
     * @param memory 已接受历史
     * @param dependencies 有界历史查询
     * @param tracker 删除屏障
     * @param ai 原始总预算
     * @param aggregation 固定收集预留
     * @param meters 无用户标签观测
     */
    public TurnTimingService(TimingModelClient model,DecisionCircuit circuit,RedisCoordination coordination,ConversationMemory memory,
                             DependencyCalls dependencies,UserWorkTracker tracker,AiProperties ai,AggregationProperties aggregation,MeterRegistry meters){
        this.model=model;this.circuit=circuit;this.coordination=coordination;this.memory=memory;this.dependencies=dependencies;
        this.tracker=tracker;this.ai=ai;this.aggregation=aggregation;this.meters=meters;
    }
    /**
     * 返回有效模型建议；为空时协调器在真实工作退出后以最新快照执行一次规则回退。
     * @param turns 唯一状态所有者
     * @param ticket 当前策略版本
     * @param snapshot 当前完整成员
     * @param deadline 策略/收集共同截止
     * @return 仍有效的严格建议，故障、取消、旧版本或熔断时为空
     */
    public TimingDecision decide(TurnCoordinator turns,TurnTicket ticket,TurnSnapshot snapshot,Deadline deadline){
        var permit=circuit.acquire();
        if(permit==null){fallback(ticket,"circuit_open");return null;}
        var outcome=DecisionCircuit.Outcome.IGNORED;String owner=null;long begin=System.nanoTime();
        try {
            var total=turns.totalDeadline(ticket);
            if(total==null || tracker.blocked(ticket.userId()) || !deadline.permits(Duration.ZERO))return null;
            // Redis 所有权按原始总预算预留发送；2s 策略截止不能再扣除整条流程的 3s Reply 预留。
            owner=coordination.acquire(ticket.userId(),total);
            if(owner==null){fallback(ticket,"dependency");return null;}
            var first=snapshot.members().get(0);final String holder=owner;
            var history=dependencies.call(deadline,() -> memory.load(first.userId(),first.messageId(),first.receivedAt(),first.sequence(),holder,deadline));
            if(turns.totalDeadline(ticket)==null || tracker.blocked(ticket.userId()) || !deadline.permits(Duration.ZERO))return null;
            Duration maxWait=TurnBudget.create(total,aggregation,ai.sendReserve()).collectionDeadline().remaining();
            Long gap=history.lastInboundAt()==null?null:Math.max(0,Duration.between(history.lastInboundAt(),first.occurredAt()).toMillis());
            var result=model.decide(new TimingRequest(snapshot.members(),history,total.elapsedMillis(),gap,maxWait,deadline,ticket.turnId(),ticket.inputVersion()));
            boolean current=turns.totalDeadline(ticket)!=null && !tracker.blocked(ticket.userId());
            if(result.inputTokens()!=null)meters.counter("ai.decision.tokens","direction","input").increment(result.inputTokens());
            if(result.outputTokens()!=null)meters.counter("ai.decision.tokens","direction","output").increment(result.outputTokens());
            BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.AI_DECISION_RESULT,"Timing model result",
                    BusinessLog.fields("turn_id",ticket.turnId(),"input_version",ticket.inputVersion(),"model",result.model(),
                            "input_tokens",result.inputTokens(),"output_tokens",result.outputTokens(),"duration_ms",result.latencyMillis(),
                            "error_type",result.errorKind(),"content",BusinessLog.fields("text",result.text(),"status",result.status(),
                                    "reasoning_tokens",result.reasoningTokens()),"outcome",!current || !deadline.permits(Duration.ZERO)?"SKIPPED":result.decision()==null?"FAILED":"SUCCESS"));
            if(!current)return null;
            if(!deadline.permits(Duration.ZERO)){outcome=DecisionCircuit.Outcome.FAILURE;fallback(ticket,"timeout");return null;}
            if(result.decision()==null){outcome=DecisionCircuit.Outcome.FAILURE;fallback(ticket,result.errorKind());return null;}
            outcome=DecisionCircuit.Outcome.SUCCESS;return result.decision();
        }catch(TimingFailure failure){
            boolean current=turns.totalDeadline(ticket)!=null && !tracker.blocked(ticket.userId());
            boolean timeout=failure.kind().equals("interrupted") && !deadline.permits(Duration.ZERO);
            if(current && (failure.upstream() || timeout))outcome=DecisionCircuit.Outcome.FAILURE;
            if(current)fallback(ticket,timeout?"timeout":failure.kind());return null;
        }catch(RuntimeException failure){
            BusinessLog.failure(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_DEPENDENCY_FAILED,"Timing dependency failed",failure,
                    BusinessLog.fields("turn_id",ticket.turnId(),"input_version",ticket.inputVersion(),"stage","timing_read","outcome","FAILED"));
            if(turns.totalDeadline(ticket)!=null && !tracker.blocked(ticket.userId()))fallback(ticket,"dependency");return null;
        }finally{
            circuit.complete(permit,outcome);meters.timer("ai.decision.duration").record(System.nanoTime()-begin,java.util.concurrent.TimeUnit.NANOSECONDS);
            if(owner!=null)try{coordination.release(ticket.userId(),owner);}
            catch(RuntimeException failure){fallback(ticket,"lock_release");}
        }
    }
    private void fallback(TurnTicket ticket,String reason){
        meters.counter("ai.decision.fallback","reason",reason==null?"invalid_output":reason).increment();
        BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_DECISION_FALLBACK,"Timing falls back to rule",
                BusinessLog.fields("turn_id",ticket.turnId(),"input_version",ticket.inputVersion(),"reason",reason,
                        "content",Map.of("circuit_open",circuit.isOpen()),"outcome","SKIPPED"));
    }
}
