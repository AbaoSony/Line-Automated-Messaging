package com.example.line.chat;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.user.UserDataDeletionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 临时 PostgreSQL 验证完整轮次事务、幂等、异步乱序、关系约束和统一删除，不调用远端服务。 */
@SpringBootTest(properties={"line.bot.channel-secret=fake-turn-secret","line.bot.channel-token=fake-turn-token"})
class ChatTurnIntegrationTest extends PostgresIntegrationSupport {
    @Autowired private ChatTurnRepository turns;
    @Autowired private ChatTurnService service;
    @Autowired private ChatRepository messages;
    @Autowired private ChatHistoryService history;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired private UserDataDeletionService deletion;
    private final Instant base=Instant.parse("2026-10-16T00:00:00.123456789Z");
    private String user(){return "U"+UUID.randomUUID().toString().replace("-","");}
    private AcceptedTurn turn(String user,String prefix,int count,long offset) {
        var inputs=new ArrayList<AcceptedTurn.Input>();
        for(int i=0;i<count;i++) {
            String id=prefix+"-"+i;var time=base.plusSeconds(offset+i);
            var content=mapper.createObjectNode().put("type","text").put("id",id).put("text","完整原文"+i)
                    .put("quoteToken","fictional-credential-must-not-persist");
            content.set("metadata",mapper.createObjectNode().put("name","完整非文本元資料"));
            inputs.add(new AcceptedTurn.Input(id,"event-"+id,"完整原文"+i,content,time,time,offset+i+1,"trace-"+id));
        }
        var first=inputs.get(0);
        return new AcceptedTurn(UUID.randomUUID(),UUID.randomUUID(),user,inputs,"唯一完整正常回覆",
                new ChatMetadata("USER",user,"AI",count==1?first.messageId():null,first.eventId(),first.receivedAt(),first.sequence(),
                        "gpt-6-astra",null,null,7L),base.plusSeconds(offset+count));
    }
    private void persist(AcceptedTurn turn){new TransactionTemplate(manager).executeWithoutResult(status -> turns.save(turn));}
    private void drain(){await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount()==0 && persistenceExecutor.getThreadPoolExecutor().getQueue().isEmpty());}
    private int count(String table,String user){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Integer.class,user);}

    @Test void asynchronousWholeTurnRestoresMissingInputsAndRepeatedSubmissionKeepsOneOutputAndOrderedMembers() {
        String user=user();var turn=turn(user,"missing-"+user,3,0);
        for(int i=0;i<10;i++)service.recordAccepted(turn);
        drain();assertThat(count("chat_message",user)).isEqualTo(4);assertThat(count("ai_turn",user)).isEqualTo(1);
        var loaded=history.findPrivate(user,"next",base.plusSeconds(30),100,200);
        assertThat(loaded.turns()).singleElement().satisfies(round -> {
            assertThat(round.turnId()).isEqualTo(turn.turnId());assertThat(round.assistantText()).isEqualTo(turn.assistantText());
            assertThat(round.members()).extracting(PrivateHistory.Member::messageId).containsExactlyElementsOf(turn.members().stream().map(AcceptedTurn.Input::messageId).toList());
            assertThat(round.members()).extracting(PrivateHistory.Member::traceId).containsExactlyElementsOf(turn.members().stream().map(AcceptedTurn.Input::traceId).toList());
        });
        for(var input:turn.members())messages.insert(user,"INBOUND",input.messageId(),"TEXT",input.content().toString(),input.occurredAt(),
                new ChatMetadata("USER",user,"LEGACY",null,input.eventId(),input.receivedAt(),input.sequence(),null,null,null,null));
        assertThat(count("chat_message",user)).isEqualTo(4);
        var output=jdbc.queryForMap("SELECT * FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",user);
        assertThat(output.get("input_tokens")).isNull();assertThat(output.get("output_tokens")).isNull();
        assertThat(output.get("model_name")).isEqualTo("gpt-6-astra");
        assertThat(jdbc.queryForList("SELECT content::text FROM chat_message WHERE user_id=?",String.class,user).toString())
                .contains("完整非文本元資料").doesNotContain("fictional-credential-must-not-persist","quoteToken");
    }
    @Test void conflictingExistingInputRollsBackRestoredInputsOutputAndAssociationWithoutOverwritingOriginal() {
        String user=user();var turn=turn(user,"conflict-"+user,2,0);var conflicting=turn.members().get(1);
        messages.insert(user,"INBOUND",conflicting.messageId(),"TEXT","{\"text\":\"原來不能覆蓋的內容\"}",base,
                new ChatMetadata("GROUP",user,"LEGACY",null,"other",base,2,null,null,null,null));
        assertThatThrownBy(() -> persist(turn)).isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        assertThat(count("chat_message",user)).isEqualTo(1);assertThat(count("ai_turn",user)).isZero();
        assertThat(count("ai_turn_member",user)).isZero();
        assertThat(jdbc.queryForObject("SELECT content->>'text' FROM chat_message WHERE line_message_id=?",String.class,conflicting.messageId()))
                .isEqualTo("原來不能覆蓋的內容");
    }
    @Test void identityCollisionAndSecondTurnReusingInputCannotCreateAnotherOutput() {
        String user=user();var turn=turn(user,"identity-"+user,2,0);persist(turn);
        var sameSend=new AcceptedTurn(UUID.randomUUID(),turn.sendAttemptId(),user,turn.members(),turn.assistantText(),turn.metadata(),turn.acceptedAt());
        assertThatThrownBy(() -> persist(sameSend)).isInstanceOf(IllegalStateException.class);
        var differentText=new AcceptedTurn(turn.turnId(),turn.sendAttemptId(),user,turn.members(),"不能覆蓋的另一回覆",turn.metadata(),turn.acceptedAt());
        assertThatThrownBy(() -> persist(differentText)).isInstanceOf(IllegalStateException.class);
        var reused=new AcceptedTurn(UUID.randomUUID(),UUID.randomUUID(),user,turn.members(),turn.assistantText(),turn.metadata(),turn.acceptedAt());
        assertThatThrownBy(() -> persist(reused)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("chat_message",user)).isEqualTo(3);assertThat(count("ai_turn",user)).isEqualTo(1);
    }
    @Test void historyAndRetentionExcludeIncompleteRoundsAndKeepRemainingOriginalMessages() {
        String user=user();var turn=turn(user,"retention-"+user,3,0);persist(turn);
        jdbc.update("DELETE FROM chat_message WHERE line_message_id=?",turn.members().get(1).messageId());
        assertThat(count("ai_turn_member",user)).isEqualTo(2);
        assertThat(history.findPrivate(user,"next",base.plusSeconds(30),100,200).turns()).isEmpty();
        assertThat(messages.deleteExpiredBatch(base.minusSeconds(1),100)).isZero();
        assertThat(count("ai_turn",user)).isZero();assertThat(count("ai_turn_member",user)).isZero();
        assertThat(count("chat_message",user)).isEqualTo(3);
    }
    @Test void historyRequiresAllMembersBeforeBoundaryAndTrimsWholeRoundsWithinRowBudget() {
        String user=user();var first=turn(user,"boundary-first-"+user,2,0);var second=turn(user,"boundary-second-"+user,2,10);
        persist(first);persist(second);
        assertThat(history.findPrivate(user,"next",base.plusSeconds(30),100,2).turns()).isEmpty();
        assertThat(history.findPrivate(user,"next",base.plusSeconds(30),100,3).turns()).extracting(PrivateHistory.Turn::turnId).containsExactly(second.turnId());
        assertThat(history.findPrivate(user,"next",base.plusMillis(500),100,200).turns()).isEmpty();
        assertThat(history.findPrivate(user,first.members().get(1).messageId(),base.plusSeconds(2),100,200).turns()).isEmpty();
    }
    @Test void foreignKeysRejectWrongUserDirectionAndConversationAndDeletingOutputCascadesAssociations() {
        String user=user();var turn=turn(user,"scope-"+user,1,0);persist(turn);
        Long inbound=jdbc.queryForObject("SELECT id FROM chat_message WHERE line_message_id=?",Long.class,turn.members().get(0).messageId());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_turn(turn_id,send_attempt_id,user_id,conversation_id,outbound_message_id,first_received_at,first_received_sequence,accepted_at,member_count)
                VALUES(?,?,?,?,?,?,1,?,1)
                """,UUID.randomUUID(),UUID.randomUUID(),user,user,inbound,Timestamp.from(base),Timestamp.from(base)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_turn_member SET user_id=? WHERE turn_id=?",user+"wrong",turn.turnId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_turn_member SET conversation_id=? WHERE turn_id=?",user+"wrong",turn.turnId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",user);
        assertThat(count("ai_turn",user)).isZero();assertThat(count("ai_turn_member",user)).isZero();assertThat(count("chat_message",user)).isEqualTo(1);
    }
    @Test void explicitDeletionPurgesNewUserStoresAndCanBeRepeated() {
        String user=user();persist(turn(user,"delete-"+user,3,0));
        deletion.deleteAll(user);deletion.deleteAll(user);drain();
        assertThat(count("ai_turn",user)).isZero();assertThat(count("ai_turn_member",user)).isZero();assertThat(count("chat_message",user)).isZero();
    }
}
