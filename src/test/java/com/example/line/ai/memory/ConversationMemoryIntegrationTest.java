package com.example.line.ai.memory;

import com.example.line.ai.coordination.RedisCoordination;
import com.example.line.chat.*;
import com.example.line.common.execution.Deadline;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/** 使用真实临时 Redis/PostgreSQL 验证关联配对、来源隔离、回源与 TTL、持有权和删除。 */
@SpringBootTest(properties={"line.bot.channel-secret=fake-memory-secret","line.bot.channel-token=fake-memory-token"})
class ConversationMemoryIntegrationTest extends PostgresIntegrationSupport {
    @Autowired private ConversationMemory memory;
    @Autowired private RedisCoordination coordination;
    @Autowired private MemoryProperties properties;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ChatHistoryService history;
    @Autowired private JdbcTemplate jdbc;
    private String user(){return "U"+UUID.randomUUID().toString().replace("-","");}
    private Deadline deadline(){return Deadline.current(Duration.ofSeconds(5));}
    private String key(String user,String suffix){return properties.namespace()+":{"+user+"}:"+suffix;}
    @Test void boundedRowsArePairedByCorrelationAndOnlyPriorPrivateNormalAiTurnsAreLoaded(){
        String user=user();Instant base=Instant.now().minusSeconds(100).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for(int index=1;index<=12;index++){
            seed(user,"INBOUND","m"+index,"TEXT","LEGACY","USER",null,"c"+index,base.plusSeconds(index),index);
            seed(user,"OUTBOUND",null,"TEXT","AI","USER","m"+index,"c"+index,base.plusSeconds(index),index);
        }
        seed(user,"OUTBOUND",null,"TEXT","FALLBACK","USER","m12","c12",base.plusSeconds(13),13);
        seed(user,"INBOUND","memory-group","TEXT","LEGACY","GROUP",null,"group",base.plusSeconds(14),14);
        seed(user,"OUTBOUND",null,"TEXT","AI","USER","m12","wrong-correlation",base.plusSeconds(14),14);
        seed(user,"INBOUND","current","TEXT","LEGACY","USER",null,"current",base.plusSeconds(20),20);
        seed(user,"INBOUND","future","TEXT","LEGACY","USER",null,"future",base.plusSeconds(21),21);
        String owner=coordination.acquire(user,deadline());
        var loaded=memory.load(user,"current",base.plusSeconds(20),20,owner,deadline());
        assertThat(loaded.turns()).hasSize(10).extracting(PrivateHistory.Turn::messageId)
                .containsExactly("m3","m4","m5","m6","m7","m8","m9","m10","m11","m12");
        assertThat(loaded.lastInboundAt()).isEqualTo(base.plusSeconds(12));
        assertThat(redis.getExpire(key(user,"context"))).isBetween(1L,properties.ttl().toSeconds());
        coordination.release(user,owner);
    }
    @Test void acceptedInMemoryTurnSurvivesAsyncDatabaseLagAndCurrentInputIsExcluded(){
        String user=user();Instant base=Instant.now();String owner=coordination.acquire(user,deadline());
        var turn=new PrivateHistory.Turn("prior","prior","原始使用者","原始回覆",base,base,1);
        memory.observe(user,"prior",base,base,1);memory.save(user,owner,new PrivateHistory(List.of(turn),base));
        memory.observe(user,"current",base.plusSeconds(1),base.plusSeconds(1),2);
        var loaded=memory.load(user,"current",base.plusSeconds(1),2,owner,deadline());
        assertThat(loaded.turns()).containsExactly(turn);assertThat(loaded.lastInboundAt()).isEqualTo(base);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
        coordination.release(user,owner);
    }
    @Test void staleOwnerCannotReleaseOverwriteOrDeleteAnotherOwner(){
        String user=user();String owner=coordination.acquire(user,deadline());
        assertThat(owner).isNotNull();assertThat(redis.getExpire(key(user,"lock"))).isPositive();
        redis.opsForValue().set(key(user,"lock"),"fictional-next-owner",Duration.ofSeconds(5));
        coordination.release(user,owner);assertThat(redis.opsForValue().get(key(user,"lock"))).isEqualTo("fictional-next-owner");
        assertThatThrownBy(() -> memory.save(user,owner,new PrivateHistory(List.of(),null))).hasMessageContaining("lock lost");
        assertThatThrownBy(() -> ((com.example.line.user.UserDataPurger)memory).purge(user)).hasMessageContaining("active Redis owner");
        assertThat(redis.opsForValue().get(key(user,"lock"))).isEqualTo("fictional-next-owner");
        redis.delete(key(user,"lock"));((com.example.line.user.UserDataPurger)memory).purge(user);
    }
    @Test void rateCounterHasAtomicTtlAndPurgeRemovesBothContextAndRateState(){
        String user=user();for(int index=0;index<properties.rateLimit();index++)assertThat(coordination.allow(user)).isTrue();
        assertThat(coordination.allow(user)).isFalse();assertThat(redis.getExpire(key(user,"rate"))).isBetween(1L,properties.rateWindow().toSeconds());
        String owner=coordination.acquire(user,deadline());memory.save(user,owner,new PrivateHistory(List.of(),null));coordination.release(user,owner);
        ((com.example.line.user.UserDataPurger)memory).purge(user);
        assertThat(redis.hasKey(key(user,"context"))).isFalse();assertThat(redis.hasKey(key(user,"rate"))).isFalse();
    }
    @Test void unknownSourceLegacyAndIncompletePairsNeverBecomeAiHistory(){
        String user=user();Instant base=Instant.now();
        seed(user,"INBOUND","unknown","TEXT","LEGACY","UNKNOWN",null,null,base,1);
        seed(user,"INBOUND","unanswered","TEXT","LEGACY","USER",null,"unanswered",base,2);
        seed(user,"INBOUND","image","IMAGE","LEGACY","USER",null,"image",base,3);
        seed(user,"OUTBOUND",null,"TEXT","AI","USER","image","image",base,3);
        seed(user,"OUTBOUND",null,"TEXT","LEGACY","USER","unanswered","unanswered",base,2);
        assertThat(history.findPrivate(user,"current",base.plusSeconds(1),4,200).turns()).isEmpty();
    }
    @Test void cacheHitStillUsesLatestNonTextInboundAndExcludesCurrentAndFutureTimes(){
        String user=user();Instant base=Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String owner=coordination.acquire(user,deadline());
        memory.observe(user,"older-local",base,base,1);
        memory.save(user,owner,new PrivateHistory(List.of(),base));
        seed(user,"INBOUND","memory-latest-image","IMAGE","LEGACY","USER",null,"image",base.plusSeconds(2),2);
        seed(user,"INBOUND","memory-current-gap","TEXT","LEGACY","USER",null,"current",base.plusSeconds(3),3);
        seed(user,"INBOUND","memory-future-gap","TEXT","LEGACY","USER",null,"future",base.plusSeconds(4),4);
        assertThat(memory.load(user,"memory-current-gap",base.plusSeconds(3),3,owner,deadline()).lastInboundAt()).isEqualTo(base.plusSeconds(2));
        coordination.release(user,owner);
    }
    private void seed(String user,String direction,String id,String type,String kind,String source,String replyTo,String correlation,Instant received,long sequence){
        jdbc.update("""
                INSERT INTO chat_message(user_id,direction,line_message_id,message_type,content,created_at,source_type,conversation_id,
                 reply_kind,in_reply_to_message_id,correlation_id,received_at,received_sequence)
                VALUES(?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?)
                """,user,direction,id,type,"{\"text\":\"完整虛構內容\"}",java.sql.Timestamp.from(received),source,user,kind,replyTo,correlation,java.sql.Timestamp.from(received),sequence);
    }
}
