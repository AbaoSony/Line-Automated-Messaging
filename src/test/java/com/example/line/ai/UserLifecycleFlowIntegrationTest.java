package com.example.line.ai;

import com.example.line.ai.coordination.SessionScheduler;
import com.example.line.ai.decision.RuleTimingPolicy;
import com.example.line.ai.generation.*;
import com.example.line.common.execution.UserWorkTracker;
import com.example.line.common.logging.EventRecorder;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.user.UserDataDeletionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 签名 Webhook、临时 PostgreSQL/Redis 和外部替身验证关注排序、取消、删除和新历史；全部数据虚构。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "line.bot.channel-secret=fictional-lifecycle-secret","line.bot.channel-token=fictional-lifecycle-access",
        "line.ai.aggregation.max-regenerations=0","line.ai.aggregation.rule-wait=100ms",
        "line.ai.dependency-timeout=2s","line.send.attempt-timeout=2s"})
@ExtendWith(OutputCaptureExtension.class)
class UserLifecycleFlowIntegrationTest extends PostgresIntegrationSupport {
    @Autowired TestRestTemplate http;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;
    @Autowired SessionScheduler scheduler;@Autowired EventRecorder recorder;
    @Autowired ThreadPoolTaskExecutor webhookAdmissionExecutor;@Autowired ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired UserDataDeletionService deletion;@Autowired UserWorkTracker tracker;
    @MockitoBean MessagingApiClient line;@MockitoSpyBean RuleTimingPolicy rules;
    @BeforeEach void defaults(){when(line.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));
        doReturn(result()).when(testModel).generate(any());}
    private ChatResult result(){return new ChatResult("完整虛構正常回覆","gpt-6-astra",12,34,1,"COMPLETED");}
    private String user(){return "U"+UUID.randomUUID().toString().replace("-","");}
    @Test void effectiveUnfollowCancelsActiveAndPendingKeepsRawDataAndRefollowStartsFreshVersion(CapturedOutput logs)throws Exception{
        String user=user();long at=Instant.now().toEpochMilli();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var requests=new CopyOnWriteArrayList<ChatRequest>();
        doAnswer(call -> {requests.add(call.getArgument(0));if(requests.size()==1){entered.countDown();ignoreInterrupt(release);}return result();}).when(testModel).generate(any());
        try{
            lifecycle(user,"follow","fresh-follow",at,false);awaitAdmissionsAfterReceipt();
            message(user,"active","第一則完整問題？");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            message(user,"pending","下一輪完整問題？");awaitAdmissionsAfterReceipt();
            lifecycle(user,"unfollow","effective-unfollow",at+1,false);awaitAdmissionsAfterReceipt();
            assertThat(status(user)).isEqualTo("UNFOLLOWED");assertThat(scheduler.isIdle()).isFalse();
            message(user,"while-unfollowed","取消關注期間的完整原文？");awaitAdmissionsAfterReceipt();
            lifecycle(user,"follow","early-refollow",at+2,false);awaitAdmissionsAfterReceipt();assertThat(status(user)).isEqualTo("FOLLOWING");
            verifyNoInteractions(line);release.countDown();drain();
            verify(testModel).generate(any());assertThat(inbound(user)).isEqualTo(3);assertThat(turns(user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.turn.completed' AND detail->'content'->>'state'='CANCELLED'",Integer.class,user)).isEqualTo(2);
            lifecycle(user,"follow","refollow",at+3,false);message(user,"after-refollow","重新關注後的新問題？");drain();
            verify(line).replyMessage(any());verify(testModel,times(2)).generate(any());assertThat(turns(user)).isEqualTo(1);
            assertThat(requests.get(1).history()).isEmpty();assertThat(requests.get(1).currentMessages()).containsExactly("重新關注後的新問題？");
            assertThat(inbound(user)).isEqualTo(4);assertThat(logs).doesNotContain("fictional-lifecycle-secret","fictional-lifecycle-access","fictional-lifecycle-reply-");
        }finally{release.countDown();}
    }
    @Test void staleAndSameTimeRedeliveredUnfollowNeverCancelNewerFollowingSession()throws Exception{
        String user=user();long at=Instant.now().toEpochMilli();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();ignoreInterrupt(release);return result();}).when(testModel).generate(any());
        try{
            lifecycle(user,"follow","newer-follow",at,false);awaitAdmissionsAfterReceipt();
            message(user,"newer-body","完整目前問題？");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            lifecycle(user,"unfollow","old-unfollow",at-1,true);lifecycle(user,"unfollow","same-time-old-unfollow",at,true);
            awaitAdmissionsAfterReceipt();assertThat(status(user)).isEqualTo("FOLLOWING");release.countDown();drain();
            verify(line).replyMessage(any());assertThat(turns(user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='user.unfollow' AND outcome='SKIPPED'",Integer.class,user)).isEqualTo(2);
        }finally{release.countDown();}
    }
    @Test void unfollowDuringRuleDecisionDiscardsLateAdviceAndNeverCallsBody()throws Exception{
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();ignoreInterrupt(release);return call.callRealMethod();}).when(rules).decide(anyList(),any());
        try{
            message(user,"decision","完整等待中的問題？");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            lifecycle(user,"unfollow","decision-unfollow",Instant.now().toEpochMilli(),false);awaitAdmissionsAfterReceipt();
            release.countDown();drain();verifyNoInteractions(testModel,line);assertThat(inbound(user)).isEqualTo(1);assertThat(status(user)).isEqualTo("UNFOLLOWED");
        }finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void unfollowDuringSendingKeepsActualAcceptedOrUnknownOutcomeWithoutFallback(boolean accepted)throws Exception{
        String user=user();var sent=new CountDownLatch(1);var response=accepted?new AcceptedAfterInterrupt():
                new CompletableFuture<com.linecorp.bot.client.base.Result<com.linecorp.bot.messaging.model.ReplyMessageResponse>>();
        when(line.replyMessage(any())).thenAnswer(call -> {sent.countDown();return response;});
        try{
            message(user,"sending","完整正在傳送的問題？");assertThat(sent.await(3,TimeUnit.SECONDS)).isTrue();
            lifecycle(user,"unfollow","sending-unfollow",Instant.now().toEpochMilli(),false);awaitAdmissionsAfterReceipt();
            if(accepted)response.complete(null);else response.completeExceptionally(new java.io.IOException("fictional-private-network-error"));
            drain();verify(line).replyMessage(any());verify(line,never()).pushMessage(any(),any());
            String terminal=jdbc.queryForObject("SELECT detail->'content'->>'state' FROM event_log WHERE user_id=? AND event='ai.turn.completed'",String.class,user);
            assertThat(terminal).isEqualTo(accepted?"ACCEPTED":"UNKNOWN");
            assertThat(turns(user)).isEqualTo(accepted?1:0);assertThat(inbound(user)).isEqualTo(1);
        }finally{response.complete(null);}
    }
    @Test void deleteDuringOldWorkWaitsAndRejectsOldCallbacksThenFreshMessageHasEmptyHistory()throws Exception{
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var requests=new CopyOnWriteArrayList<ChatRequest>();
        doAnswer(call -> {requests.add(call.getArgument(0));if(requests.size()==1){entered.countDown();ignoreInterrupt(release);}return result();}).when(testModel).generate(any());
        try{
            message(user,"delete-old","刪除前完整問題？");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var deleting=CompletableFuture.runAsync(() -> deletion.deleteAll(user));await().atMost(Duration.ofSeconds(2)).until(() -> tracker.blocked(user));
            lifecycle(user,"follow","during-delete",Instant.now().toEpochMilli(),false);awaitAdmissionsAfterReceipt();assertThat(deleting).isNotDone();
            release.countDown();deleting.get(5,TimeUnit.SECONDS);drain();assertThat(inbound(user)).isZero();assertThat(turns(user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM line_user WHERE user_id=?",Integer.class,user)).isZero();verifyNoInteractions(line);
            message(user,"delete-fresh","刪除後新問題？");drain();verify(line).replyMessage(any());
            assertThat(requests.get(1).history()).isEmpty();assertThat(turns(user)).isEqualTo(1);assertThat(inbound(user)).isEqualTo(1);
        }finally{release.countDown();}
    }
    private void awaitAdmissionsAfterReceipt(){await().atMost(Duration.ofSeconds(3)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);awaitAdmissions();}
    private void drain(){awaitAdmissionsAfterReceipt();await().atMost(Duration.ofSeconds(5)).until(scheduler::isIdle);
        await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount()==0 && persistenceExecutor.getQueueSize()==0);await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);}
    private String status(String user){return jdbc.queryForObject("SELECT status FROM line_user WHERE user_id=?",String.class,user);}
    private int inbound(String user){return jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='INBOUND'",Integer.class,user);}
    private int turns(String user){return jdbc.queryForObject("SELECT count(*) FROM ai_turn WHERE user_id=?",Integer.class,user);}
    private void lifecycle(String user,String type,String id,long at,boolean redelivery)throws Exception{
        send(Map.of("type",type,"mode","active","timestamp",at,"webhookEventId",user+"-"+id,"deliveryContext",Map.of("isRedelivery",redelivery),"source",Map.of("type","user","userId",user)));
    }
    private void message(String user,String id,String text)throws Exception{
        send(Map.of("type","message","mode","active","timestamp",Instant.now().toEpochMilli(),"webhookEventId",user+"-"+id,"deliveryContext",Map.of("isRedelivery",false),
                "source",Map.of("type","user","userId",user),"replyToken","fictional-lifecycle-reply-"+id,"message",Map.of("type","text","id",user+"-message-"+id,"text",text)));
    }
    private void send(Map<String,Object> event)throws Exception{
        byte[] bytes=mapper.writeValueAsBytes(Map.of("destination","Ufictional-destination","events",List.of(event)));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("fictional-lifecycle-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Line-Signature",Base64.getEncoder().encodeToString(mac.doFinal(bytes)));
        assertThat(http.postForEntity("/line/webhook",new HttpEntity<>(bytes,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private static void ignoreInterrupt(CountDownLatch release){boolean interrupted=false;
        try{while(true)try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return;}catch(InterruptedException failure){interrupted=true;}}
        finally{if(interrupted)Thread.currentThread().interrupt();}}
    /** 模拟取消后仍明确获得成功结果的已在途发送；不能把真实接受篡改成 CANCELLED。 */
    private static class AcceptedAfterInterrupt extends CompletableFuture<com.linecorp.bot.client.base.Result<com.linecorp.bot.messaging.model.ReplyMessageResponse>> {
        @Override public com.linecorp.bot.client.base.Result<com.linecorp.bot.messaging.model.ReplyMessageResponse> get(long timeout,TimeUnit unit)
                throws ExecutionException,TimeoutException {
            boolean interrupted=false;long end=System.nanoTime()+unit.toNanos(timeout);
            try{while(true)try{return super.get(Math.max(0,end-System.nanoTime()),TimeUnit.NANOSECONDS);}
                catch(InterruptedException failure){interrupted=true;}}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
}
