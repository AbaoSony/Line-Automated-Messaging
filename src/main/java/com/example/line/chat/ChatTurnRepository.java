package com.example.line.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 在外层业务事务内维护完整成功轮次；原始入站乱序时仅补缺，不覆盖已有业务数据。 */
@Repository
class ChatTurnRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ChatRepository messages;

    ChatTurnRepository(JdbcTemplate jdbc,ObjectMapper mapper,ChatRepository messages) {
        this.jdbc=jdbc;this.mapper=mapper;this.messages=messages;
    }

    void save(AcceptedTurn turn) {
        // 两个稳定身份按固定顺序取事务锁，重复异步提交不会产生第二出站或彼此死锁。
        java.util.stream.Stream.of(turn.turnId().toString(),turn.sendAttemptId().toString()).distinct().sorted()
                .forEach(id -> jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",id));
        var existing=jdbc.queryForList("SELECT * FROM ai_turn WHERE turn_id=? OR send_attempt_id=?",turn.turnId(),turn.sendAttemptId());
        if(!existing.isEmpty()) {
            if(existing.size()!=1 || !same(existing.get(0),turn))throw new IllegalStateException("Accepted turn identity conflict");
            return;
        }
        var inputs=new ArrayList<Long>();
        for(var input:turn.members()) {
            var meta=new ChatMetadata("USER",turn.userId(),"LEGACY",null,input.eventId(),input.receivedAt(),input.sequence(),null,null,null,null);
            messages.insert(turn.userId(),"INBOUND",input.messageId(),"TEXT",input.content().toString(),input.occurredAt(),meta);
            var rows=jdbc.queryForList("SELECT * FROM chat_message WHERE direction='INBOUND' AND line_message_id=?",input.messageId());
            if(rows.size()!=1 || !matchesInput(rows.get(0),turn.userId(),input))throw new IllegalStateException("Stored input conflicts with accepted snapshot");
            inputs.add(((Number)rows.get(0).get("id")).longValue());
        }
        var first=turn.members().get(0);var meta=turn.metadata();
        var content=mapper.createObjectNode().put("text",turn.assistantText());
        if(turn.members().size()>1) {
            var members=mapper.createArrayNode();
            for(var input:turn.members()) {
                var node=mapper.createObjectNode().put("userId",turn.userId()).put("eventId",input.eventId())
                        .put("messageId",input.messageId()).put("text",input.text()).put("occurredAt",input.occurredAt().toString())
                        .put("receivedAt",input.receivedAt().toString()).put("sequence",input.sequence()).put("traceId",input.traceId());
                node.set("content",input.content());members.add(node);
            }
            content.set("ai_turn",mapper.createObjectNode().put("turn_id",turn.turnId().toString()).set("members",members));
        }
        Long outbound=jdbc.queryForObject("""
                INSERT INTO chat_message(user_id,direction,message_type,content,created_at,source_type,conversation_id,reply_kind,
                  in_reply_to_message_id,correlation_id,received_at,received_sequence,model_name,input_tokens,output_tokens,latency_ms)
                VALUES(?,'OUTBOUND','TEXT',?::jsonb,?,'USER',?,'AI',?,?,?,?,?,?,?,?) RETURNING id
                """,Long.class,turn.userId(),content.toString(),ts(turn.acceptedAt()),turn.userId(),
                turn.members().size()==1?first.messageId():null,first.eventId(),ts(first.receivedAt()),first.sequence(),
                meta.model(),meta.inputTokens(),meta.outputTokens(),meta.latencyMs());
        jdbc.update("""
                INSERT INTO ai_turn(turn_id,send_attempt_id,user_id,conversation_id,outbound_message_id,
                  first_received_at,first_received_sequence,accepted_at,member_count) VALUES(?,?,?,?,?,?,?,?,?)
                """,turn.turnId(),turn.sendAttemptId(),turn.userId(),turn.userId(),outbound,ts(first.receivedAt()),first.sequence(),ts(turn.acceptedAt()),inputs.size());
        for(int index=0;index<inputs.size();index++)jdbc.update("""
                INSERT INTO ai_turn_member(turn_id,member_order,inbound_message_id,user_id,conversation_id,webhook_event_id,trace_id)
                VALUES(?,?,?,?,?,?,?)
                """,turn.turnId(),index+1,inputs.get(index),turn.userId(),turn.userId(),turn.members().get(index).eventId(),turn.members().get(index).traceId());
    }

    private boolean same(Map<String,Object> stored,AcceptedTurn turn) {
        if(!turn.turnId().equals(stored.get("turn_id")) || !turn.sendAttemptId().equals(stored.get("send_attempt_id"))
                || !turn.userId().equals(stored.get("user_id")) || ((Number)stored.get("member_count")).intValue()!=turn.members().size()
                || !sameTime(stored.get("accepted_at"),turn.acceptedAt()))return false;
        var output=jdbc.queryForMap("SELECT * FROM chat_message WHERE id=?",stored.get("outbound_message_id"));
        var meta=turn.metadata();
        if(!turn.assistantText().equals(json(output.get("content")).path("text").asText()) || !"AI".equals(output.get("reply_kind"))
                || !Objects.equals(meta.model(),output.get("model_name")) || !Objects.equals(meta.inputTokens(),output.get("input_tokens"))
                || !Objects.equals(meta.outputTokens(),output.get("output_tokens")) || !Objects.equals(meta.latencyMs(),output.get("latency_ms")))return false;
        var rows=jdbc.queryForList("""
                SELECT i.*,m.member_order,m.webhook_event_id,m.trace_id FROM ai_turn_member m JOIN chat_message i ON i.id=m.inbound_message_id
                WHERE m.turn_id=? ORDER BY m.member_order
                """,turn.turnId());
        if(rows.size()!=turn.members().size())return false;
        for(int i=0;i<rows.size();i++)if(((Number)rows.get(i).get("member_order")).intValue()!=i+1
                || !matchesInput(rows.get(i),turn.userId(),turn.members().get(i))
                || !Objects.equals(rows.get(i).get("webhook_event_id"),turn.members().get(i).eventId())
                || !Objects.equals(rows.get(i).get("trace_id"),turn.members().get(i).traceId()))return false;
        return true;
    }
    private boolean matchesInput(Map<String,Object> row,String user,AcceptedTurn.Input input) {
        return user.equals(row.get("user_id")) && user.equals(row.get("conversation_id")) && "USER".equals(row.get("source_type"))
                && "TEXT".equals(row.get("message_type")) && "LEGACY".equals(row.get("reply_kind"))
                && input.messageId().equals(row.get("line_message_id")) && input.eventId().equals(row.get("correlation_id"))
                && sameTime(row.get("created_at"),input.occurredAt()) && sameTime(row.get("received_at"),input.receivedAt())
                && Objects.equals(row.get("received_sequence"),input.sequence()) && input.content().equals(json(row.get("content")));
    }
    private com.fasterxml.jackson.databind.JsonNode json(Object value) {
        try { return mapper.readTree(value.toString()); }
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid stored turn content",failure); }
    }
    private static boolean sameTime(Object value,Instant expected) {
        return value instanceof Timestamp time && time.toInstant().equals(ts(expected).toInstant());
    }
    private static Timestamp ts(Instant time) {
        // PostgreSQL 以微秒保存时间，显式截断保证重复校验和独立入站写入使用同一精度。
        return Timestamp.from(time.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    void delete(String user) { jdbc.update("DELETE FROM ai_turn WHERE user_id=?",user); }
}
