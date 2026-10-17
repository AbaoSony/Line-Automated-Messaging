package com.example.line.ai;

import com.example.line.ai.coordination.SessionScheduler;
import com.example.line.ai.decision.RuleTimingPolicy;
import com.example.line.ai.generation.*;
import com.example.line.ai.memory.MemoryProperties;
import com.example.line.common.logging.EventRecorder;
import com.example.line.common.persistence.PostgresIntegrationSupport;
import com.example.line.user.UserDataDeletionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 真实虚构 HTTP 入站配合 PostgreSQL/Redis 容器，验证生产默认聚合、静默、一次发送与删除。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "line.bot.channel-secret=fictional-aggregate-secret","line.bot.channel-token=fictional-aggregate-access",
        "line.send.attempt-timeout=100ms","line.ai.dependency-timeout=2s"})
@ExtendWith(OutputCaptureExtension.class)
class AggregatedFlowIntegrationTest extends PostgresIntegrationSupport {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionScheduler scheduler;
    @Autowired ThreadPoolTaskExecutor webhookAdmissionExecutor;
    @Autowired ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired EventRecorder recorder;
    @Autowired StringRedisTemplate redis;
    @Autowired MemoryProperties memory;
    @Autowired UserDataDeletionService deletion;
    @MockitoBean MessagingApiClient line;
    @MockitoSpyBean RuleTimingPolicy rules;
    @MockitoSpyBean AiProperties ai;
    @MockitoSpyBean com.example.line.ai.memory.RedisConversationMemory cache;

    @BeforeEach void lineAccepts() { when(line.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null)); }
    private String user() { return "U"+UUID.randomUUID().toString().replace("-",""); }
    private String key(String user,String suffix) { return memory.namespace()+":{"+user+"}:"+suffix; }
    private ChatResult result(String text) { return new ChatResult(text,"gpt-6-astra",123,45,7,"COMPLETED"); }

    @Test void threeRawMembersGenerateOneCompleteSnapshotUseLatestCredentialAndNeverPairPartialHistory(CapturedOutput logs) throws Exception {
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        holdFirstRule(entered,release);
        var captured=new CopyOnWriteArrayList<ChatRequest>();
        doAnswer(call -> { captured.add(call.getArgument(0));return result("完整虛構聚合回覆"); }).when(testModel).generate(any());
        List<String> texts=List.of("等我一下我想說","完整第二則保留\n換行","就這些？");
        try {
            send(user,"aggregate-first",texts.get(0));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            send(user,"aggregate-second",texts.get(1));send(user,"aggregate-third",texts.get(2));
            awaitInbound(user,3);release.countDown();drain();
            assertThat(captured).singleElement().satisfies(request -> {
                assertThat(request.currentMessages()).containsExactlyElementsOf(texts);assertThat(request.history()).isEmpty();
                assertThat(request.systemPrompt()).contains("第一次聊天");
            });
            verify(line).replyMessage(argThat(request -> request.replyToken().equals("fictional-reply-aggregate-third")));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isEqualTo(1);
            var outbound=jdbc.queryForMap("SELECT * FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",user);
            assertThat(outbound.get("reply_kind")).isEqualTo("AI");assertThat(outbound.get("in_reply_to_message_id")).isNull();
            var stored=mapper.readTree(outbound.get("content").toString()).path("ai_turn");
            assertThat(UUID.fromString(stored.path("turn_id").asText())).isNotNull();
            assertThat(stored.path("members")).hasSize(3);
            for(int i=0;i<texts.size();i++)assertThat(stored.path("members").get(i).path("text").asText()).isEqualTo(texts.get(i));
            assertThat(redis.opsForValue().get(key(user,"rate"))).isEqualTo("3");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.turn.completed'",Integer.class,user)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT trace_id) FROM event_log WHERE user_id=? AND event='ai.turn.completed'",Integer.class,user)).isEqualTo(3);
            for(int i=0;i<3;i++)send(user,List.of("aggregate-first","aggregate-second","aggregate-third").get(i),texts.get(i));
            drain();verify(testModel).generate(any());verify(line).replyMessage(any());
            send(user,"aggregate-followup","後續問題？");drain();
            assertThat(captured).hasSize(2);assertThat(captured.get(1).history()).containsExactly(
                    new ChatRequest.HistoryMessage("user",texts.get(0)),new ChatRequest.HistoryMessage("user",texts.get(1)),
                    new ChatRequest.HistoryMessage("user",texts.get(2)),new ChatRequest.HistoryMessage("assistant","完整虛構聚合回覆"));
            assertThat(logs).contains("完整第二則保留","ai.turn.completed").doesNotContain("fictional-aggregate-secret","fictional-aggregate-access","fictional-reply-");
            deletion.deleteAll(user);drain();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
            assertThat(redis.hasKey(key(user,"context-v2"))).isFalse();assertThat(redis.hasKey(key(user,"rate"))).isFalse();
        } finally { release.countDown(); }
    }
    @Test void rateLimitCountsEachMessageBeforeAdmissionAndRejectedCredentialCannotReplaceAcceptedMember() throws Exception {
        String user=user();redis.opsForValue().set(key(user,"rate"),"11",Duration.ofSeconds(60));
        send(user,"rate-accepted","完整保留的原文");send(user,"rate-rejected","超過限流也要保留原文");drain();
        assertThat(redis.opsForValue().get(key(user,"rate"))).isEqualTo("13");awaitInbound(user,2);
        var request=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(testModel).generate(request.capture());
        assertThat(request.getValue().currentMessages()).containsExactly("完整保留的原文");
        verify(line).replyMessage(argThat(sent -> sent.replyToken().equals("fictional-reply-rate-accepted")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='user.rate_limited'",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detail->>'reply_kind' FROM event_log WHERE user_id=? AND event='ai.reply.skipped'",String.class,user)).isEqualTo("RATE_LIMITED");
    }
    @Test void unknownReplyCompletesAllMemberDedupWithoutFallbackOrSecondSend() throws Exception {
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);holdFirstRule(entered,release);
        when(line.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fictional-private-sdk-envelope")));
        try {
            send(user,"unknown-first","等我一下");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            send(user,"unknown-second","完整第二則？");awaitInbound(user,2);release.countDown();drain();
            send(user,"unknown-first","等我一下");send(user,"unknown-second","完整第二則？");drain();
            verify(testModel).generate(any());verify(line).replyMessage(any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.turn.completed' AND detail->'content'->>'state'='UNKNOWN'",Integer.class,user)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event IN ('ai.fallback.used','webhook.event_failed')",Integer.class,user)).isZero();
        } finally { release.countDown(); }
    }
    @Test void generationFailureAndOversizedInputStaySilentWithCompleteInboundRecords() throws Exception {
        String user=user();doThrow(new ModelFailure("timeout")).when(testModel).generate(any());
        send(user,"silent-failure","完整問題？");drain();
        send(user,"silent-long","虛".repeat(4097));drain();
        verifyNoInteractions(line);verify(testModel).generate(any());awaitInbound(user,2);
        assertThat(jdbc.queryForObject("SELECT length(content->>'text') FROM chat_message WHERE user_id=? AND line_message_id='message-silent-long'",Integer.class,user)).isEqualTo(4097);
        assertThat(jdbc.queryForList("SELECT detail->>'reply_kind' FROM event_log WHERE user_id=? AND event='ai.reply.skipped' ORDER BY detail->>'reply_kind'",String.class,user))
                .containsExactly("FALLBACK","INPUT_TOO_LONG");
    }
    @Test void explicitTechnicalSwitchUsesOneSharedCooldownAcrossMultipleRejectedMessages() throws Exception {
        doReturn(true).when(ai).technicalRepliesEnabled();String user=user();
        redis.opsForValue().set(key(user,"rate"),"12",Duration.ofSeconds(60));
        send(user,"hint-first","第一則完整原文");send(user,"hint-second","第二則完整原文");send(user,"hint-third","第三則完整原文");drain();
        verifyNoInteractions(testModel);verify(line).replyMessage(any());awaitInbound(user,3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.reply.skipped' AND detail->>'reason'='technical_reply_cooldown'",Integer.class,user)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"incomplete","input_budget","dependency","lock"})
    void defaultTurnFlowSilencesEveryDependencyAndBudgetClassWithoutFalseOutbound(String scenario) throws Exception {
        String user=user(),id="turn-silent-"+scenario;
        String text=scenario.equals("input_budget")?"虛".repeat(4000)+"？":"完整虛構問題？";
        switch(scenario) {
            case "incomplete" -> doReturn(new ChatResult("完整未完成輸出","gpt-6-astra",100,1024,25,"INCOMPLETE","max_output_tokens",1024)).when(testModel).generate(any());
            case "dependency" -> doThrow(new IllegalStateException("fictional-private-dependency-password")).when(cache).load(eq(user),any(),any(),anyLong(),any(),any());
            case "lock" -> redis.opsForValue().set(key(user,"lock"),"fictional-foreign-owner",Duration.ofSeconds(3));
            default -> { }
        }
        send(user,id,text);drain();verifyNoInteractions(line);awaitInbound(user,1);
        String kind=switch(scenario) { case "input_budget"->"INPUT_TOO_LONG";case "dependency"->"SERVICE_UNAVAILABLE";case "lock"->"BUSY";default->"FALLBACK"; };
        assertThat(jdbc.queryForObject("SELECT detail->>'reply_kind' FROM event_log WHERE user_id=? AND event='ai.reply.skipped'",String.class,user)).isEqualTo(kind);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT content->>'text' FROM chat_message WHERE user_id=? AND direction='INBOUND'",String.class,user)).isEqualTo(text);
        if(scenario.equals("incomplete")) {
            verify(testModel).generate(any());
            assertThat(jdbc.queryForObject("SELECT output_tokens FROM event_log WHERE user_id=? AND event='ai.generate.failed'",Integer.class,user)).isEqualTo(1024);
        } else verifyNoInteractions(testModel);
    }
    @Test void explicitlyEnabledFallbackIsRecordedOnceButDoesNotEnterNextNormalHistory() throws Exception {
        doReturn(true).when(ai).technicalRepliesEnabled();String user=user();
        doThrow(new ModelFailure("timeout")).when(testModel).generate(any());send(user,"enabled-fallback","完整問題？");drain();
        var captured=new java.util.concurrent.atomic.AtomicReference<ChatRequest>();
        doAnswer(call -> {captured.set(call.getArgument(0));return result("正常虛構回覆");}).when(testModel).generate(any());
        send(user,"after-fallback","下一則完整問題？");drain();verify(line,times(2)).replyMessage(any());
        assertThat(captured.get().history()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.fallback.used'",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND' AND reply_kind='FALLBACK' AND model_name IS NULL",Integer.class,user)).isEqualTo(1);
        assertThat(redis.opsForValue().get(key(user,"context-v2"))).doesNotContain(ai.fallback());
    }
    @Test void deletionWaitsForActualCancelledModelExitAndPreventsOldReplyAndWriteback() throws Exception {
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();boolean interrupted=false;
            while(true)try {assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();break;}catch(InterruptedException failure){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();return result("取消後不得送出的虛構回覆");
        }).when(testModel).generate(any());
        try {
            send(user,"delete-active","完整問題？");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var deletionResult=CompletableFuture.runAsync(() -> deletion.deleteAll(user));
            assertThatThrownBy(() -> deletionResult.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();deletionResult.get(5,TimeUnit.SECONDS);drain();verifyNoInteractions(line);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
            assertThat(redis.hasKey(key(user,"context-v2"))).isFalse();
        } finally { release.countDown(); }
    }
    private void holdFirstRule(CountDownLatch entered,CountDownLatch release) {
        var first=new AtomicBoolean(true);
        doAnswer(call -> {
            if(first.compareAndSet(true,false)) {entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();}
            return call.callRealMethod();
        }).when(rules).decide(anyList(),any());
    }
    private void awaitInbound(String user,int count) {
        await().atMost(Duration.ofSeconds(4)).until(() -> jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='INBOUND'",Integer.class,user)==count);awaitAdmissions();
    }
    private void drain() {
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);
        awaitAdmissions();await().atMost(Duration.ofSeconds(6)).until(scheduler::isIdle);
        await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount()==0 && persistenceExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
    }
    private void send(String user,String id,String text) throws Exception {
        byte[] body=mapper.writeValueAsBytes(Map.of("destination","Ufictional-destination","events",List.of(Map.of(
                "type","message","mode","active","timestamp",Instant.now().toEpochMilli(),"webhookEventId",id,
                "deliveryContext",Map.of("isRedelivery",false),"replyToken","fictional-reply-"+id,
                "source",Map.of("type","user","userId",user),"message",Map.of("type","text","id","message-"+id,"text",text)))));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("fictional-aggregate-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Line-Signature",Base64.getEncoder().encodeToString(mac.doFinal(body)));
        assertThat(http.postForEntity("/line/webhook",new HttpEntity<>(body,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
