package com.example.line.ai;

import com.example.line.ai.coordination.SessionScheduler;
import com.example.line.ai.decision.RuleTimingPolicy;
import com.example.line.ai.generation.*;
import com.example.line.ai.memory.RedisConversationMemory;
import com.example.line.common.logging.EventRecorder;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.user.UserDataDeletionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 生产轮次入口到真实 LINE SDK/本地 HTTP，覆盖终态、全体成员去重、删除和迟到结果。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "line.bot.channel-secret=fictional-reply-http-secret","line.bot.channel-token=fictional-production-unused-access",
        "line.send.attempt-timeout=2s","line.ai.dependency-timeout=2s"})
@Import(ReplyHttpTestConfig.class) @ExtendWith(OutputCaptureExtension.class)
class ReplyOutcomeIntegrationTest extends PostgresIntegrationSupport {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionScheduler scheduler;
    @Autowired ThreadPoolTaskExecutor webhookAdmissionExecutor;
    @Autowired ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired EventRecorder recorder;
    @Autowired ReplyHttpStub stub;
    @Autowired UserDataDeletionService deletion;
    @Autowired com.example.line.common.execution.UserWorkTracker tracker;
    @MockitoSpyBean RuleTimingPolicy rules;
    @MockitoSpyBean AiProperties ai;
    @MockitoSpyBean TurnReplyService replies;
    @MockitoSpyBean RedisConversationMemory memory;
    @MockitoSpyBean com.example.line.messaging.LineMessageService messages;
    private final List<ReplyHttpStub.Plan> held=new ArrayList<>();
    @BeforeEach void emptyStub(){stub.requests.clear();stub.plans.clear();stub.pushes.set(0);}
    @AfterEach void releaseStub(){held.forEach(plan -> plan.release.countDown());}
    private String user(){return "U"+UUID.randomUUID().toString().replace("-","");}

    @ParameterizedTest @ValueSource(ints={200,400,401,403,408,409,429,500,503,307})
    void realHttpOutcomeCompletesEveryMemberOnceAndCannotConsumeRedeliveryAgain(int status,CapturedOutput logs) throws Exception {
        String user=user(),prefix="http-"+status;var plan=stub.plan(status,ReplyHttpStub.Mode.NORMAL);
        sendCollected(user,prefix);
        assertThat(plan.finished.await(3,TimeUnit.SECONDS)).isTrue();drain();
        String expected=status==200?"ACCEPTED":status>=400 && status<500?"FAILED":"UNKNOWN";
        assertTerminal(user,expected,2);assertThat(stub.requests).hasSize(1);
        var sent=stub.requests.peek();assertThat(sent.method()).isEqualTo("POST");assertThat(sent.path()).isEqualTo("/v2/bot/message/reply");
        assertThat(sent.authorization()).isEqualTo("Bearer fictional-line-http-access");
        assertThat(sent.body().path("replyToken").asText()).isEqualTo("fictional-reply-"+prefix+"-second");
        assertThat(sent.body().path("messages").get(0).path("text").asText()).isEqualTo("完整虛構正文");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isEqualTo(status==200?1:0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_turn WHERE user_id=?",Integer.class,user)).isEqualTo(status==200?1:0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_turn_member WHERE user_id=?",Integer.class,user)).isEqualTo(status==200?2:0);
        if(status!=200)assertThat(jdbc.queryForObject("SELECT detail->>'delivery_outcome' FROM event_log WHERE user_id=? AND event='line.reply.failed'",String.class,user))
                .isEqualTo(expected.equals("FAILED")?"REJECTED":"UNKNOWN");
        send(user,prefix+"-first","完整第一則",true);send(user,prefix+"-second","完整第二則？",true);drain();
        assertThat(stub.requests).hasSize(1);assertThat(stub.pushes.get()).isZero();verify(testModel).generate(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='webhook.event_failed'",Integer.class,user)).isZero();
        assertThat(logs).doesNotContain("fictional-line-http-access","fictional-reply-","fictional-private-http-envelope");
    }
    @ParameterizedTest @ValueSource(strings={"timeout","reset","bad_json"})
    void ambiguousWireFailureRemainsUnknownAfterLateSuccessAndExplicitFallbackEnablement(String scenario) throws Exception {
        doReturn(true).when(ai).technicalRepliesEnabled();String user=user(),prefix="wire-"+scenario;
        var plan=stub.plan(200,switch(scenario){case "timeout"->ReplyHttpStub.Mode.HOLD;case "reset"->ReplyHttpStub.Mode.RESET;default->ReplyHttpStub.Mode.BAD_JSON;});held.add(plan);
        sendCollected(user,prefix);assertThat(plan.received.await(3,TimeUnit.SECONDS)).isTrue();drain();assertTerminal(user,"UNKNOWN",2);
        plan.release.countDown();assertThat(plan.finished.await(3,TimeUnit.SECONDS)).isTrue();
        send(user,prefix+"-first","完整第一則",true);send(user,prefix+"-second","完整第二則？",true);drain();
        assertThat(stub.requests).hasSize(1);assertThat(stub.pushes.get()).isZero();verify(testModel).generate(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_turn WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.fallback.used'",Integer.class,user)).isZero();
    }
    @Test void messageArrivingDuringSendingGetsItsOwnTurnAndCannotReplaceSendingCredential() throws Exception {
        String user=user();var plan=stub.plan(200,ReplyHttpStub.Mode.HOLD);held.add(plan);
        var generated=new CopyOnWriteArrayList<ChatRequest>();doAnswer(call -> {generated.add(call.getArgument(0));return result();}).when(testModel).generate(any());
        sendCollected(user,"sending-active");assertThat(plan.received.await(3,TimeUnit.SECONDS)).isTrue();
        send(user,"sending-next","下一輪完整問題？",false);awaitInbound(user,3);plan.release.countDown();drain();
        assertThat(stub.requests).hasSize(2);assertThat(generated).hasSize(2);
        var sent=List.copyOf(stub.requests);
        assertThat(sent.get(0).body().path("replyToken").asText()).isEqualTo("fictional-reply-sending-active-second");
        assertThat(sent.get(1).body().path("replyToken").asText()).isEqualTo("fictional-reply-sending-next");
        assertThat(generated.get(0).currentMessages()).containsExactly("完整第一則","完整第二則？");
        assertThat(generated.get(1).currentMessages()).containsExactly("下一輪完整問題？");
        assertThat(generated.get(1).history()).containsExactly(new ChatRequest.HistoryMessage("user","完整第一則"),
                new ChatRequest.HistoryMessage("user","完整第二則？"),new ChatRequest.HistoryMessage("assistant","完整虛構正文"));
    }
    @Test void acceptedReplyAndCacheFailureStillKeepAllMembersConsumed() throws Exception {
        String user=user();doThrow(new IllegalStateException("fictional-private-cache-password")).when(memory).save(eq(user),any(),any());
        send(user,"cache-after-accept","完整單條問題？",false);drain();assertTerminal(user,"ACCEPTED",1);
        send(user,"cache-after-accept","完整單條問題？",true);drain();
        assertThat(stub.requests).hasSize(1);verify(testModel).generate(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isEqualTo(1);
        var next=new AtomicReference<ChatRequest>();
        doAnswer(call -> {next.set(call.getArgument(0));return result();}).when(testModel).generate(any());
        send(user,"cache-failed-followup","緊接著下一條完整問題？",false);drain();
        assertThat(next.get().history()).containsExactly(new ChatRequest.HistoryMessage("user","完整單條問題？"),
                new ChatRequest.HistoryMessage("assistant","完整虛構正文"));
        assertThat(stub.requests).hasSize(2);
    }
    @Test void deletionDuringSendingCancelsLocallyWithoutPretendingRemoteRequestWasNeverSent() throws Exception {
        String user=user();var plan=stub.plan(200,ReplyHttpStub.Mode.HOLD);held.add(plan);
        sendCollected(user,"delete-sending");assertThat(plan.received.await(3,TimeUnit.SECONDS)).isTrue();
        deletion.deleteAll(user);drain();plan.release.countDown();assertThat(plan.finished.await(3,TimeUnit.SECONDS)).isTrue();
        send(user,"delete-sending-first","完整第一則",true);send(user,"delete-sending-second","完整第二則？",true);drain();
        assertThat(stub.requests).hasSize(1);assertThat(stub.pushes.get()).isZero();verify(testModel).generate(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_user_id=? AND event='user.data.deleted' AND outcome='SUCCESS'",Integer.class,user)).isEqualTo(1);
    }
    @Test void deletionAfterAcceptedSendWaitsForRealWorkerExitAndDoesNotTurnAcceptanceIntoResend() throws Exception {
        String user=user();var accepted=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {call.callRealMethod();accepted.countDown();awaitIgnoringInterrupt(release);return null;}).when(replies).send(any(),any(),anyList(),any(),anyString(),anyString(),any(),any());
        try {
            sendCollected(user,"accepted-delete");assertThat(accepted.await(3,TimeUnit.SECONDS)).isTrue();
            var deleting=CompletableFuture.runAsync(() -> deletion.deleteAll(user));
            assertThatThrownBy(() -> deleting.get(50,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();deleting.get(5,TimeUnit.SECONDS);drain();
            send(user,"accepted-delete-first","完整第一則",true);send(user,"accepted-delete-second","完整第二則？",true);drain();
            assertThat(stub.requests).hasSize(1);verify(testModel).generate(any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
        } finally {release.countDown();}
    }
    @Test void deletionAfterSendClaimButBeforeSdkIsKnownNotSentAndStillConsumesTheWholeTurn(CapturedOutput logs) throws Exception {
        String user=user();var claimed=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {claimed.countDown();awaitIgnoringInterrupt(release);return call.callRealMethod();})
                .when(messages).replyText(anyString(),anyString(),any(com.example.line.common.execution.Deadline.class));
        try {
            sendCollected(user,"claimed-delete");assertThat(claimed.await(3,TimeUnit.SECONDS)).isTrue();
            var deleting=CompletableFuture.runAsync(() -> deletion.deleteAll(user));
            await().atMost(Duration.ofSeconds(3)).until(() -> tracker.blocked(user));
            assertThat(deleting.isDone()).isFalse();release.countDown();deleting.get(5,TimeUnit.SECONDS);drain();
            send(user,"claimed-delete-first","完整第一則",true);send(user,"claimed-delete-second","完整第二則？",true);drain();
            assertThat(stub.requests).isEmpty();verify(testModel).generate(any());
            assertThat(logs).contains("\"delivery_outcome\":\"NOT_SENT\"","\"state\":\"FAILED\"");
        } finally {release.countDown();}
    }
    @Test void documentedUnknownWireAndEventExamplesMatchRealSdkAndAllMemberDedup() throws Exception {
        var base=java.nio.file.Path.of("docs/examples");
        byte[] body=java.nio.file.Files.readAllBytes(base.resolve("webhook/webhook-aggregate-unknown.json"));
        var incoming=mapper.readTree(body);String user=incoming.path("events").get(0).path("source").path("userId").asText();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(call -> {if(first.compareAndSet(true,false)){entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();}return call.callRealMethod();}).when(rules).decide(anyList(),any());
        var plan=stub.plan(503,ReplyHttpStub.Mode.NORMAL);
        try {
            sendRaw(body);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();awaitInbound(user,2);release.countDown();
            assertThat(plan.finished.await(3,TimeUnit.SECONDS)).isTrue();drain();assertTerminal(user,"UNKNOWN",2);
            assertThat(stub.requests).hasSize(1);
            assertThat(stub.requests.peek().body()).isEqualTo(mapper.readTree(java.nio.file.Files.readAllBytes(base.resolve("reply/reply-aggregate-unknown-request.json"))));
            var expected=mapper.readTree(java.nio.file.Files.readAllBytes(base.resolve("reply/reply-unknown-event.json")));
            var actual=jdbc.queryForMap("SELECT * FROM event_log WHERE user_id=? AND event='line.reply.failed'",user);
            for(String key:List.of("event","level","webhook_event_id","user_id","outcome","error_type"))assertThat(actual.get(key)).isEqualTo(expected.path(key).asText());
            assertThat(((Number)actual.get("http_status")).intValue()).isEqualTo(expected.path("http_status").asInt());
            assertThat(mapper.readTree(actual.get("detail").toString())).isEqualTo(expected.path("detail"));
            for(var event:incoming.path("events"))((com.fasterxml.jackson.databind.node.ObjectNode)event.path("deliveryContext")).put("isRedelivery",true);
            sendRaw(mapper.writeValueAsBytes(incoming));drain();assertThat(stub.requests).hasSize(1);verify(testModel).generate(any());
        } finally {release.countDown();}
    }
    private void sendCollected(String user,String prefix) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(call -> {if(first.compareAndSet(true,false)){entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();}return call.callRealMethod();}).when(rules).decide(anyList(),any());
        try {
            send(user,prefix+"-first","完整第一則",false);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            send(user,prefix+"-second","完整第二則？",false);awaitInbound(user,2);
        } finally {release.countDown();}
    }
    @BeforeEach void normalModel(){doReturn(result()).when(testModel).generate(any());}
    private ChatResult result(){return new ChatResult("完整虛構正文","gpt-6.1-sol",10,10,1,"COMPLETED");}
    private void assertTerminal(String user,String state,int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.turn.completed' AND detail->'content'->>'state'=?",Integer.class,user,state)).isEqualTo(count);
    }
    private void awaitInbound(String user,int count){await().atMost(Duration.ofSeconds(3)).until(() -> jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='INBOUND'",Integer.class,user)==count);}
    private void drain(){
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(6)).until(scheduler::isIdle);
        await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount()==0 && persistenceExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) throws Exception {
        boolean interrupted=false;
        try {while(true)try{assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue();return;}catch(InterruptedException failure){interrupted=true;}}
        finally {if(interrupted)Thread.currentThread().interrupt();}
    }
    private void send(String user,String id,String text,boolean redelivery) throws Exception {
        byte[] body=mapper.writeValueAsBytes(Map.of("destination","Ufictional-destination","events",List.of(Map.of(
                "type","message","mode","active","timestamp",Instant.now().toEpochMilli(),"webhookEventId",id,
                "deliveryContext",Map.of("isRedelivery",redelivery),"replyToken","fictional-reply-"+id,
                "source",Map.of("type","user","userId",user),"message",Map.of("type","text","id","message-"+id,"text",text)))));
        sendRaw(body);
    }
    private void sendRaw(byte[] body) throws Exception {
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("fictional-reply-http-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Line-Signature",Base64.getEncoder().encodeToString(mac.doFinal(body)));
        assertThat(http.postForEntity("/line/webhook",new HttpEntity<>(body,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
