package com.example.line.ai;

import com.example.line.ai.coordination.SessionScheduler;
import com.example.line.ai.decision.*;
import com.example.line.ai.generation.*;
import com.example.line.common.execution.UserWorkTracker;
import com.example.line.common.logging.EventRecorder;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.user.UserDataDeletionService;
import com.fasterxml.jackson.databind.*;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 真实 Webhook/临时存储与模型替身验证 MODEL 编排、版本、熔断及文档示例；实际 SDK 另由本地 HTTP 测试覆盖。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "line.bot.channel-secret=fictional-model-flow-secret","line.bot.channel-token=fictional-model-flow-access",
        "line.ai.decision.failure-threshold=2","line.ai.decision.cooldown=5s"})
@TestPropertySource(properties="line.ai.decision.mode=MODEL")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class ModelTimingFlowIntegrationTest extends PostgresIntegrationSupport {
    @Autowired TestRestTemplate http;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;
    @Autowired SessionScheduler scheduler;@Autowired EventRecorder recorder;@Autowired DecisionCircuit circuit;
    @Autowired ThreadPoolTaskExecutor webhookAdmissionExecutor;@Autowired ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired UserDataDeletionService deletion;@Autowired UserWorkTracker tracker;
    @MockitoBean TimingModelClient timing;@MockitoBean MessagingApiClient line;
    @BeforeEach void defaults(){
        doAnswer(call -> result(now())).when(timing).decide(any());
        doReturn(new ChatResult("完整虛構模型正文","gpt-6-astra",12,34,1,"COMPLETED")).when(testModel).generate(any());
        when(line.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));
    }
    private String user(){return "U"+UUID.randomUUID().toString().replace("-","");}
    private TimingDecision now(){return new TimingDecision(TimingDecision.Action.REPLY_NOW,Duration.ZERO,TimingDecision.Reason.COMPLETE_INPUT);}
    private TimingResult result(TimingDecision decision){return new TimingResult(decision,"完整虛構策略原文","gpt-6-astra",30,40,10,1,"COMPLETED",null);}
    @Test void documentedWaitWakesWithinOriginalWindowThenNowProducesExactlyOneNormalReply(CapturedOutput logs)throws Exception{
        String user=user();var calls=new CopyOnWriteArrayList<TimingRequest>();
        var waiting=Files.readString(Path.of("docs/examples/reply/timing-wait-output.json"));
        var immediate=Files.readString(Path.of("docs/examples/reply/timing-now-output.json"));
        doAnswer(call -> {
            calls.add(call.getArgument(0));String text=calls.size()==1?waiting:immediate;var json=mapper.readTree(text);
            return new TimingResult(new TimingDecision(TimingDecision.Action.valueOf(json.path("action").asText()),Duration.ofMillis(json.path("wait_ms").asLong()),
                    TimingDecision.Reason.valueOf(json.path("reason_code").asText())),text,"gpt-6-astra",30,40,10,1,"COMPLETED",null);
        }).when(timing).decide(any());
        send(user,"wait","我想說一件事");drain();verify(timing,times(2)).decide(any());verify(testModel).generate(any());verify(line).replyMessage(any());
        assertThat(calls).hasSize(2);assertThat(calls.get(1).ageMillis()).isGreaterThanOrEqualTo(calls.get(0).ageMillis());
        assertThat(calls.get(1).maxWait()).isLessThan(calls.get(0).maxWait());assertThat(calls.get(0).turnId()).isEqualTo(calls.get(1).turnId());
        var texts=jdbc.queryForList("SELECT detail->'content'->>'text' FROM event_log WHERE user_id=? AND event='ai.decision.result' ORDER BY ts",String.class,user);
        assertThat(texts).containsExactly(waiting,immediate);assertThat(circuit.isOpen()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_turn WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(logs).doesNotContain("fictional-model-flow-access","fictional-reply-","fictional-model-flow-secret");
    }
    @Test void dirtyInputsDiscardOldModelOutputAndEvaluateOnlyLatestCompleteSnapshot()throws Exception{
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new CopyOnWriteArrayList<TimingRequest>();
        doAnswer(call -> {calls.add(call.getArgument(0));if(calls.size()==1){entered.countDown();awaitIgnoringInterrupt(release);}return result(now());}).when(timing).decide(any());
        try {
            send(user,"dirty-first","完整第一則");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            send(user,"dirty-second","完整第二則");send(user,"dirty-third","就這些？");awaitInbound(user,3);release.countDown();drain();
            assertThat(calls).hasSize(2);assertThat(calls.get(1).members()).extracting(m -> m.text()).containsExactly("完整第一則","完整第二則","就這些？");
            verify(line).replyMessage(argThat(r -> r.replyToken().equals("fictional-reply-dirty-third")));verify(testModel).generate(any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.decision.result' AND outcome='SKIPPED'",Integer.class,user)).isEqualTo(1);
            assertThat(circuit.isOpen()).isFalse();assertThat(jdbc.queryForObject("SELECT member_count FROM ai_turn WHERE user_id=?",Integer.class,user)).isEqualTo(3);
        }finally{release.countDown();}
    }
    @Test void invalidResponseFallsBackOnceToRuleAndNormalBodyWithoutSendingTechnicalHint()throws Exception{
        String user=user();doReturn(new TimingResult(null,"完整非法策略原文","gpt-6-astra",30,40,10,1,"COMPLETED","invalid_output")).when(timing).decide(any());
        send(user,"invalid","完整問題？");drain();verify(timing).decide(any());verify(testModel).generate(any());verify(line).replyMessage(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.decision.fallback'",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reply_kind FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",String.class,user)).isEqualTo("AI");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.fallback.used'",Integer.class,user)).isZero();
    }
    @Test void consecutiveUpstreamFailuresOpenGlobalCircuitAndOtherUserUsesRuleWithoutModelCall()throws Exception{
        doThrow(new TimingFailure("timeout",true)).when(timing).decide(any());
        for(int i=0;i<2;i++){send(user(),"fault-"+i,"完整問題？");drain();}
        assertThat(circuit.isOpen()).isTrue();String skipped=user();send(skipped,"circuit","另一個人的完整問題？");drain();
        verify(timing,times(2)).decide(any());verify(testModel,times(3)).generate(any());verify(line,times(3)).replyMessage(any());
        assertThat(jdbc.queryForObject("SELECT detail->>'reason' FROM event_log WHERE user_id=? AND event='ai.decision.fallback'",String.class,skipped)).isEqualTo("circuit_open");
    }
    @Test void userCancellationDoesNotCountAsUpstreamFailureAndCannotSendLateAdviceOrRestoreDeletedData()throws Exception{
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();awaitIgnoringInterrupt(release);throw new TimingFailure("network",true);}).when(timing).decide(any());
        try {
            send(user,"delete","完整未完成的輸入");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var deleting=CompletableFuture.runAsync(() -> deletion.deleteAll(user));await().atMost(Duration.ofSeconds(2)).until(() -> tracker.blocked(user));
            assertThatThrownBy(() -> deleting.get(50,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();deleting.get(5,TimeUnit.SECONDS);drain();verifyNoInteractions(line);verifyNoInteractions(testModel);
            assertThat(circuit.isOpen()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
        }finally{release.countDown();}
    }
    @Test void globalDecisionSubLimitKeepsOnlyOneModelCallWhileOtherUsersWaitWithoutCallingInParallel()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var active=new AtomicInteger();var max=new AtomicInteger();
        doAnswer(call -> {int count=active.incrementAndGet();max.accumulateAndGet(count,Math::max);try{entered.countDown();awaitIgnoringInterrupt(release);return result(now());}
            finally{active.decrementAndGet();}}).when(timing).decide(any());
        try {
            send(user(),"shared-first","完整第一位問題？");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            send(user(),"shared-second","完整第二位問題？");send(user(),"shared-third","完整第三位問題？");
            verify(timing,after(100).times(1)).decide(any());release.countDown();drain();
            assertThat(max).hasValue(1);verify(timing,times(3)).decide(any());verify(line,times(3)).replyMessage(any());
        }finally{release.countDown();}
    }
    private void send(String user,String id,String text)throws Exception{
        byte[] bytes=mapper.writeValueAsBytes(Map.of("destination","Ufictional-destination","events",List.of(Map.of(
                "type","message","mode","active","timestamp",Instant.now().toEpochMilli(),"webhookEventId",id,"deliveryContext",Map.of("isRedelivery",false),
                "replyToken","fictional-reply-"+id,"source",Map.of("type","user","userId",user),"message",Map.of("type","text","id",user+"-"+id,"text",text)))));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("fictional-model-flow-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Line-Signature",Base64.getEncoder().encodeToString(mac.doFinal(bytes)));
        assertThat(http.postForEntity("/line/webhook",new HttpEntity<>(bytes,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private void awaitInbound(String user,int count){await().atMost(Duration.ofSeconds(3)).until(() -> jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='INBOUND'",Integer.class,user)==count);awaitAdmissions();}
    private void drain(){
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);
        awaitAdmissions();await().atMost(Duration.ofSeconds(6)).until(scheduler::isIdle);
        await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount()==0 && persistenceExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch){boolean interrupted=false;
        while(true)try{assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue();break;}catch(InterruptedException failure){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();}
}
