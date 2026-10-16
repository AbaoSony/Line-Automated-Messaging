package com.example.line.chat;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

/** 在临时 PostgreSQL 的独立旧版 schema 上执行真实 V3→V4，验证不猜测、不覆盖及重复启动稳定性。 */
@SpringBootTest(properties={"line.bot.channel-secret=fake-migration-secret","line.bot.channel-token=fake-migration-token"})
class ChatTurnMigrationIntegrationTest extends PostgresIntegrationSupport {
    @Autowired private DataSource source;
    @Autowired private ObjectMapper mapper;
    private final Instant time=Instant.parse("2026-10-15T00:00:00Z");
    private static final String USER="Uxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx5";
    @Test void migrationKeepsOnlyUnambiguousSingleAndCompleteTransitionalRoundsAndPreservesAllRawMessages() throws Exception {
        String schema="migration_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("3").load().migrate();
        try(var connection=source.getConnection()) {
            String originalSchema=connection.getSchema();
            try {
                connection.setSchema(schema);var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                var valid=pair(jdbc,"single","USER","TEXT","完整舊原文");
                pair(jdbc,"unknown","UNKNOWN","TEXT","未知來源完整原文");
                pair(jdbc,"group","GROUP","TEXT","群組原文");
                pair(jdbc,"image","USER","IMAGE","不是文字");
                pair(jdbc,"blank","USER","TEXT"," ");
                var duplicate=pair(jdbc,"duplicate","USER","TEXT","不能猜選哪條回覆");
                jdbc.update("""
                        INSERT INTO chat_message(user_id,direction,message_type,content,created_at,source_type,conversation_id,reply_kind,
                         in_reply_to_message_id,correlation_id,received_at,received_sequence)
                        SELECT user_id,direction,message_type,content,created_at,source_type,conversation_id,reply_kind,
                         in_reply_to_message_id,correlation_id,received_at,received_sequence FROM chat_message WHERE id=?
                        """,duplicate);
                var missing=pair(jdbc,"missing","USER","TEXT","完整但關聯缺失");
                jdbc.update("UPDATE chat_message SET correlation_id=NULL WHERE id=?",missing);
                var zero=pair(jdbc,"zero","USER","TEXT","舊資料沒有有效接收序號");
                jdbc.update("UPDATE chat_message SET received_sequence=0 WHERE id=? OR line_message_id='zero'",zero);
                UUID stable=UUID.randomUUID();var transition=transitional(jdbc,"transition",stable);
                var partial=transitional(jdbc,"partial",UUID.randomUUID());
                jdbc.update("DELETE FROM chat_message WHERE line_message_id='partial-1'");
                var malformed=transitional(jdbc,"malformed",UUID.randomUUID());
                jdbc.update("UPDATE chat_message SET content=jsonb_set(content,'{ai_turn,members,1,receivedAt}','\"invalid-time\"') WHERE id=?",malformed);
                int before=jdbc.queryForObject("SELECT count(*) FROM chat_message",Integer.class);
                var oldContent=jdbc.queryForObject("SELECT content::text FROM chat_message WHERE id=?",String.class,transition);
                var flyway=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();
                flyway.migrate();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message",Integer.class)).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT content::text FROM chat_message WHERE id=?",String.class,transition)).isEqualTo(oldContent);
                assertThat(jdbc.queryForList("SELECT outbound_message_id FROM ai_turn ORDER BY outbound_message_id",Long.class)).containsExactly(valid,transition);
                assertThat(jdbc.queryForObject("SELECT turn_id FROM ai_turn WHERE outbound_message_id=?",UUID.class,transition)).isEqualTo(stable);
                assertThat(jdbc.queryForObject("SELECT member_count FROM ai_turn WHERE turn_id=?",Integer.class,stable)).isEqualTo(2);
                assertThat(jdbc.queryForList("SELECT trace_id FROM ai_turn_member WHERE turn_id=? ORDER BY member_order",String.class,stable))
                        .containsExactly("trace-transition-0","trace-transition-1");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_turn_member m JOIN ai_turn t USING(turn_id) WHERE t.outbound_message_id=? AND trace_id IS NULL",Integer.class,valid)).isEqualTo(1);
                var identities=jdbc.queryForList("SELECT turn_id,send_attempt_id FROM ai_turn ORDER BY outbound_message_id");
                flyway.migrate();assertThat(jdbc.queryForList("SELECT turn_id,send_attempt_id FROM ai_turn ORDER BY outbound_message_id")).isEqualTo(identities);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_turn WHERE outbound_message_id IN (?,?)",Integer.class,partial,malformed)).isZero();
            } finally {connection.setSchema(originalSchema);}
        } finally {new JdbcTemplate(source).execute("DROP SCHEMA "+schema+" CASCADE");}
    }
    private Long pair(JdbcTemplate jdbc,String id,String sourceType,String type,String text) {
        insert(jdbc,"INBOUND",id,sourceType,type,"LEGACY",null,id,mapper.createObjectNode().put("text",text).toString(),time,1);
        return insert(jdbc,"OUTBOUND",null,sourceType,"TEXT","AI",id,id,"{\"text\":\"完整正常舊回覆\"}",time,1);
    }
    private Long transitional(JdbcTemplate jdbc,String prefix,UUID id) {
        var members=mapper.createArrayNode();
        for(int i=0;i<2;i++) {
            String message=prefix+"-"+i;var at=time.plusSeconds(i);
            insert(jdbc,"INBOUND",message,"USER","TEXT","LEGACY",null,message,"{\"text\":\"完整聚合原文\"}",at,i+1);
            members.add(mapper.createObjectNode().put("userId",USER).put("eventId",message).put("messageId",message).put("text","完整聚合原文")
                    .put("occurredAt",at.toString()).put("receivedAt",at.toString()).put("sequence",i+1).put("traceId","trace-"+message));
        }
        var content=mapper.createObjectNode().put("text","完整聚合舊回覆");
        content.set("ai_turn",mapper.createObjectNode().put("turn_id",id.toString()).set("members",members));
        return insert(jdbc,"OUTBOUND",null,"USER","TEXT","AI",null,prefix+"-0",content.toString(),time,1);
    }
    private Long insert(JdbcTemplate jdbc,String direction,String id,String source,String type,String kind,String replyTo,
                        String correlation,String content,Instant at,long seq) {
        return jdbc.queryForObject("""
                INSERT INTO chat_message(user_id,direction,line_message_id,message_type,content,created_at,source_type,conversation_id,
                  reply_kind,in_reply_to_message_id,correlation_id,received_at,received_sequence)
                VALUES(?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?) RETURNING id
                """,Long.class,USER,direction,id,type,content,Timestamp.from(at),source,USER,kind,replyTo,correlation,Timestamp.from(at),seq);
    }
}
