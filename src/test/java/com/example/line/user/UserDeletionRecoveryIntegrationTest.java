package com.example.line.user;

import com.example.line.ai.memory.MemoryProperties;
import com.example.line.audit.AuditService;
import com.example.line.common.execution.UserWorkTracker;
import com.example.line.common.logging.EventRecorder;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 使用真实临时存储验证删除超时/Redis 未释放时数据库不丢数据，恢复后可幂等重试。 */
@SpringBootTest(properties={"line.bot.channel-secret=fictional-deletion-recovery-secret","line.bot.channel-token=fictional-deletion-recovery-access"})
class UserDeletionRecoveryIntegrationTest extends PostgresIntegrationSupport {
    @Autowired UserService users;@Autowired UserWorkTracker tracker;@Autowired UserDataDeletionService deletion;
    @Autowired JdbcTemplate jdbc;@Autowired StringRedisTemplate redis;@Autowired MemoryProperties memory;
    @Autowired List<UserDataPurger> purgers;@Autowired List<UserDeletionGuard> guards;@Autowired AuditService audit;
    @Autowired PlatformTransactionManager manager;@Autowired EventRecorder recorder;
    @Test void deletionTimeoutRetainsBusinessDataReleasesBarrierAndCanBeRetriedAfterRealWorkExits(){
        String user=seed();var before=jdbc.queryForList("SELECT * FROM chat_message WHERE user_id=?",user);
        var shortWait=new UserDataDeletionService(purgers,audit,guards,tracker,manager,new DeletionProperties(Duration.ofMillis(40)));
        try(var pending=tracker.register(user)){
            assertThatThrownBy(() -> shortWait.deleteAll(user)).isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
            assertThat(tracker.blocked(user)).isFalse();assertThat(jdbc.queryForList("SELECT * FROM chat_message WHERE user_id=?",user)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM line_user WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        }
        deletion.deleteAll(user);assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
        await().atMost(Duration.ofSeconds(3)).until(recorder::isIdle);
        assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE target_user_id=? AND event='user.data.deleted'",String.class,user)).containsExactly("SUCCESS");
        // 成功删除历史审计，所以此前失败审计被删除；本次成功操作的审计随后保留。
        deletion.deleteAll(user);assertThat(jdbc.queryForObject("SELECT count(*) FROM line_user WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void redisOwnerFailureRollsBackDatabaseAndRetryRemovesBothContextFormats(){
        String user=seed();String prefix=memory.namespace()+":{"+user+"}:";
        redis.opsForValue().set(prefix+"lock","fictional-owner",Duration.ofSeconds(10));
        redis.opsForValue().set(prefix+"context","fictional-old-cache",Duration.ofSeconds(10));
        redis.opsForValue().set(prefix+"context-v2","fictional-current-cache",Duration.ofSeconds(10));
        try{
            assertThatThrownBy(() -> deletion.deleteAll(user)).isInstanceOf(IllegalStateException.class).hasMessageContaining("active Redis owner");
            assertThat(tracker.blocked(user)).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM line_user WHERE user_id=?",Integer.class,user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isEqualTo(1);
            redis.delete(prefix+"lock");deletion.deleteAll(user);
            assertThat(redis.hasKey(prefix+"context")).isFalse();assertThat(redis.hasKey(prefix+"context-v2")).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
        }finally{redis.delete(List.of(prefix+"lock",prefix+"context",prefix+"context-v2"));}
    }
    private String seed(){String user="U"+UUID.randomUUID().toString().replace("-","");
        users.recordStatus(user,true,Instant.now());awaitAdmissions();await().atMost(Duration.ofSeconds(3)).until(recorder::isIdle);
        jdbc.update("INSERT INTO chat_message(user_id,direction,message_type,content) VALUES(?,'INBOUND','TEXT','{\"text\":\"完整虛構刪除測試原文\"}')",user);return user;}
}
