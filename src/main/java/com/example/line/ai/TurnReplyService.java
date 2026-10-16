package com.example.line.ai;

import com.example.line.ai.aggregation.InboundMember;
import com.example.line.ai.coordination.*;
import com.example.line.ai.decision.DecisionProperties;
import com.example.line.ai.generation.ChatResult;
import com.example.line.ai.memory.ConversationMemory;
import com.example.line.chat.*;
import com.example.line.common.execution.*;
import com.example.line.common.logging.*;
import com.example.line.messaging.LineMessageService;
import com.example.line.messaging.LineSendException;
import com.example.line.user.UserDataPurger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;

/** 将轮次发送权与实际 LINE 结果衔接；技术话术共享冷却，发送结果未知不得补发。 */
@Service
public class TurnReplyService implements UserDataPurger {
    private final AiProperties ai;
    private final DecisionProperties decision;
    private final LineMessageService messages;
    private final ChatMessageService chat;
    private final ConversationMemory memory;
    private final UserWorkTracker tracker;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final LinkedHashMap<String, Long> hints = new LinkedHashMap<>(16, .75f, true);

    /**
     * 注入发送、记录及删除能力；冷却只保存有限用户标识和单调时间，不保存凭证。
     * @param ai 技术提示总开关及容量
     * @param decision 共享技术提示冷却
     * @param messages 一次 Reply 封装
     * @param chat 原文异步存储
     * @param memory 正常单成员历史缓存
     * @param tracker 删除屏障
     * @param mapper 业务 JSON
     * @param meters 无用户标签指标
     */
    public TurnReplyService(AiProperties ai, DecisionProperties decision, LineMessageService messages,
                            ChatMessageService chat, ConversationMemory memory, UserWorkTracker tracker,
                            ObjectMapper mapper, MeterRegistry meters) {
        this.ai=ai; this.decision=decision; this.messages=messages; this.chat=chat;
        this.memory=memory; this.tracker=tracker; this.mapper=mapper; this.meters=meters;
    }
    /**
     * 取得唯一发送权后只尝试一次，已接受回复的缓存失败不改变发送结果。
     * @param turns 唯一协调器
     * @param ticket 当前生成版本
     * @param members 当前完整有序成员
     * @param owner Redis 所有者
     * @param text 完整输出
     * @param kind 正常 AI 或固定技术类型
     * @param result 正常模型用量，可为空
     * @param history 生成前的已接受历史，可为空
     */
    public void send(TurnCoordinator turns, TurnTicket ticket, List<InboundMember> members, String owner,
                     String text, String kind, ChatResult result, PrivateHistory history) {
        if (!kind.equals("AI") && !technicalAllowed(ticket.userId(),kind)) {
            turns.finishUnsent(ticket,TurnResult.Reason.TECHNICAL_SILENT); return;
        }
        if (tracker.blocked(ticket.userId())) { skipped("deletion_barrier",kind); return; }
        try (var permit=turns.claimSending(ticket)) {
            if (permit==null) { skipped("stale_or_expired",kind); turns.finishUnsent(ticket,TurnResult.Reason.SEND_FAILURE); return; }
            noteSend(ticket.userId());
            if (kind.equals("FALLBACK")) {
                meters.counter("ai.fallback.attempted").increment();
                BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_FALLBACK_USED,"AI fallback attempted",
                        BusinessLog.fields("reply_kind",kind,"content",Map.of("text",text),"outcome","SKIPPED"));
            }
            // claim 与发送异常处理之间不能进入生成兜底，否则未知结果可能再次消费凭证。
            try { messages.replyText(permit.token(),text,permit.deadline()); }
            catch (LineSendException failure) {
                boolean known=failure.getReason()==LineSendException.Reason.CLIENT_ERROR;
                turns.finishSending(ticket,known?TurnState.FAILED:TurnState.UNKNOWN); return;
            } catch (RuntimeException failure) {
                turns.finishSending(ticket,TurnState.UNKNOWN); dependencyFailure("send_unknown",failure); return;
            }
            turns.finishSending(ticket,TurnState.ACCEPTED);
            var first=members.get(0);
            // 多成员关联表尚未迁移前，不能把回复错误配给其中一条并污染历史。
            var content=mapper.createObjectNode().put("text",text);
            if (members.size()>1) content.set("ai_turn",mapper.valueToTree(BusinessLog.fields("turn_id",ticket.turnId(),"members",members)));
            chat.recordMessage(first.userId(),"OUTBOUND",null,"TEXT",content,Instant.now(),metadata(first,kind,kind.equals("AI")?result:null,members.size()==1));
            meters.counter("ai.reply.accepted","kind",kind).increment();
            if (kind.equals("FALLBACK")) meters.counter("ai.fallback.accepted").increment();
            if (kind.equals("AI") && members.size()==1 && history!=null && owner!=null
                    && !tracker.blocked(first.userId()) && permit.deadline().permits(Duration.ZERO)) {
                var rounds=new ArrayList<>(history.turns());
                rounds.removeIf(turn -> turn.messageId().equals(first.messageId()));
                rounds.add(new PrivateHistory.Turn(first.messageId(),first.eventId(),first.text(),text,
                        first.occurredAt(),first.receivedAt(),first.sequence()));
                try { memory.save(first.userId(),owner,new PrivateHistory(List.copyOf(rounds),first.occurredAt())); }
                catch (RuntimeException failure) { dependencyFailure("cache_write_after_reply",failure); }
            }
        }
    }
    /**
     * 在独立有界提示执行器中处理未准入消息；默认静默，拒绝提示也不能重试。
     * @param member 已完整记录的拒绝成员
     * @param token 仅本次即时提示内存凭证
     * @param deadline 原始入口截止
     * @param kind 固定技术类型
     */
    public void rejected(InboundMember member, String token, Deadline deadline, String kind) {
        if (!technicalAllowed(member.userId(),kind) || tracker.blocked(member.userId()) || !deadline.permits(Duration.ZERO)) return;
        try {
            messages.replyText(token,text(kind),deadline);
            chat.recordMessage(member.userId(),"OUTBOUND",null,"TEXT",mapper.createObjectNode().put("text",text(kind)),
                    Instant.now(),metadata(member,kind,null));
            meters.counter("ai.reply.accepted","kind",kind).increment();
        } catch (RuntimeException failure) { dependencyFailure("rejected_hint",failure); }
    }
    /**
     * 选择已配置的固定技术话术。
     * @param kind 固定类型
     * @return 原始配置话术
     */
    public String text(String kind) {
        return switch(kind) { case "BUSY"->ai.busy(); case "RATE_LIMITED"->ai.rateLimited();
            case "INPUT_TOO_LONG"->ai.inputTooLong(); case "SERVICE_UNAVAILABLE"->ai.unavailable(); default->ai.fallback(); };
    }
    private boolean technicalAllowed(String user,String kind) {
        if (!ai.technicalRepliesEnabled()) { skipped("technical_replies_disabled",kind); return false; }
        synchronized (hints) {
            long now=System.nanoTime(); Long previous=hints.get(user);
            if (previous!=null && now-previous<decision.technicalReplyCooldown().toNanos()) {
                skipped("technical_reply_cooldown",kind); return false;
            }
            remember(user,now); return true;
        }
    }
    private void noteSend(String user) { synchronized(hints) { remember(user,System.nanoTime()); } }
    private void remember(String user,long now) {
        hints.entrySet().removeIf(entry -> now-entry.getValue()>=decision.technicalReplyCooldown().toNanos());
        hints.put(user,now); while(hints.size()>ai.maxUsers()) hints.remove(hints.keySet().iterator().next());
    }
    private ChatMetadata metadata(InboundMember first,String kind,ChatResult result) {
        return metadata(first,kind,result,true);
    }
    private ChatMetadata metadata(InboundMember first,String kind,ChatResult result,boolean single) {
        return new ChatMetadata("USER",first.userId(),kind,single?first.messageId():null,first.eventId(),first.receivedAt(),first.sequence(),
                result==null?null:result.modelName(),result==null?null:result.inputTokens(),result==null?null:result.outputTokens(),result==null?null:result.latencyMs());
    }
    private void skipped(String reason,String kind) {
        meters.counter("ai.reply.skipped","reason",reason).increment();
        BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_REPLY_SKIPPED,"AI reply skipped",
                BusinessLog.fields("reason",reason,"reply_kind",kind,"outcome","SKIPPED"));
    }
    private void dependencyFailure(String stage,RuntimeException failure) {
        BusinessLog.failure(LoggerFactory.getLogger(getClass()),Level.WARN,LogEvents.AI_DEPENDENCY_FAILED,"AI dependency unavailable",failure,
                BusinessLog.fields("stage",stage,"outcome","FAILED"));
    }
    /**
     * 用户工作排空后删除其可丢弃技术提示冷却。
     * @param userId 完整用户标识
     */
    @Override public void purge(String userId) { synchronized(hints) { hints.remove(userId); } }
}
