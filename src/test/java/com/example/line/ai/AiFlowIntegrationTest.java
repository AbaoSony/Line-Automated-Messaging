package com.example.line.ai;

import com.example.line.ai.coordination.SessionScheduler;
import com.example.line.ai.generation.*;
import com.example.line.ai.memory.*;
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
import java.util.concurrent.atomic.AtomicInteger;
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

/** 用完整虚构 HTTP 私聊验证 AI、角色历史、分类出站、故障隔离、凭证禁记和并发删除屏障。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "line.bot.channel-secret=fake-ai-flow-secret","line.bot.channel-token=fake-ai-flow-token","line.send.attempt-timeout=100ms",
        "line.ai.technical-replies-enabled=true","line.ai.turns.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
class AiFlowIntegrationTest extends PostgresIntegrationSupport {
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionScheduler scheduler;
    @Autowired private ThreadPoolTaskExecutor webhookAdmissionExecutor;
    @Autowired private ThreadPoolTaskExecutor webhookExecutor;
    @Autowired private ThreadPoolTaskExecutor persistenceExecutor;
    @Autowired private EventRecorder recorder;
    @Autowired private StringRedisTemplate redis;
    @Autowired private MemoryProperties memoryProperties;
    @Autowired private UserDataDeletionService deletion;
    @Autowired private UserWorkTracker tracker;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;
    @MockitoBean private MessagingApiClient line;
    @MockitoSpyBean private RedisConversationMemory memory;
    @MockitoSpyBean private AiProperties aiProperties;
    @BeforeEach void prepareLine(){when(line.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(null));}
    private String user(){return "U"+UUID.randomUUID().toString().replace("-","");}
    private String key(String user,String suffix){return memoryProperties.namespace()+":{"+user+"}:"+suffix;}
    private ChatResult result(String text){return new ChatResult(text,"gpt-6.1-sol",123,45,7,"COMPLETED");}
    @ParameterizedTest @ValueSource(strings={"timeout","empty","incomplete","too_long","input_limit","rate","dependency","lock"})
    void disabledTechnicalRepliesKeepInboundAndFailureEventsWithoutSendingOrRetrying(String scenario) throws Exception {
        doReturn(false).when(aiProperties).technicalRepliesEnabled();
        String user=user(),id="fictional-silent-"+scenario;
        String text=scenario.equals("input_limit")?"訊".repeat(4000):"完整虛構入站";
        switch(scenario) {
            case "timeout" -> doThrow(new ModelFailure("timeout")).when(testModel).generate(any());
            case "empty" -> doReturn(result(" ")).when(testModel).generate(any());
            case "incomplete" -> doReturn(new ChatResult("未完成虛構內容","gpt-6-astra",100,1024,25,"INCOMPLETE","max_output_tokens",1024)).when(testModel).generate(any());
            case "too_long" -> doReturn(result("虛".repeat(5001))).when(testModel).generate(any());
            case "rate" -> redis.opsForValue().set(key(user,"rate"),"12",Duration.ofSeconds(60));
            case "dependency" -> doThrow(new IllegalStateException("fake-private-redis-password")).when(memory).load(eq(user),any(),any(),anyLong(),any(),any());
            case "lock" -> redis.opsForValue().set(key(user,"lock"),"fictional-foreign-owner",Duration.ofSeconds(5));
            default -> { }
        }
        double attempts=meters.find("ai.fallback.attempted").counter()==null?0:meters.get("ai.fallback.attempted").counter().count();
        var response=send(user,id,text,"user","active");drain();
        send(user,id,text,"user","active");drain();
        verifyNoInteractions(line);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT content->>'text' FROM chat_message WHERE user_id=? AND direction='INBOUND'",String.class,user)).isEqualTo(text);
        String kind=switch(scenario){case "input_limit"->"INPUT_TOO_LONG";case "rate"->"RATE_LIMITED";case "dependency"->"SERVICE_UNAVAILABLE";case "lock"->"BUSY";default->"FALLBACK";};
        var detail=mapper.readTree(jdbc.queryForObject("SELECT detail::text FROM event_log WHERE trace_id=? AND event='ai.reply.skipped'",String.class,response.getHeaders().getFirst("X-Trace-Id")));
        assertThat(detail.path("reason").asText()).isEqualTo("technical_replies_disabled");
        assertThat(detail.path("reply_kind").asText()).isEqualTo(kind);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.fallback.used'",Integer.class,user)).isZero();
        String failureEvent=switch(kind){case "FALLBACK"->"ai.generate.failed";case "RATE_LIMITED"->"user.rate_limited";case "SERVICE_UNAVAILABLE"->"ai.dependency.failed";case "BUSY"->"lock.timeout";default->null;};
        if(failureEvent!=null)assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event=?",Integer.class,user,failureEvent)).isEqualTo(1);
        assertThat(meters.find("ai.fallback.attempted").counter()==null?0:meters.get("ai.fallback.attempted").counter().count()).isEqualTo(attempts);
        if(kind.equals("FALLBACK"))verify(testModel).generate(any());else verifyNoInteractions(testModel);
        String cached=redis.opsForValue().get(key(user,"context-v2"));
        if(cached!=null)assertThat(mapper.readTree(cached).path("turns")).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"expired","full"})
    void silentQueueRejectionPreservesEarlierWorkAndDoesNotConsumeRejectedReplyToken(String scenario) throws Exception {
        doReturn(false).when(aiProperties).technicalRepliesEnabled();
        doReturn(scenario.equals("expired")?Duration.ofMillis(50):Duration.ofSeconds(2)).when(aiProperties).queueWait();
        doReturn(1).when(aiProperties).userQueueCapacity();
        String user=user(),prefix="fictional-queue-silent-"+scenario;
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return result("正常虛構回覆");}).when(testModel).generate(any());
        try {
            send(user,prefix+"-first","第一則","user","active");assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            send(user,prefix+"-second","第二則","user","active");
            String rejected=prefix+"-second";
            if(scenario.equals("full")){rejected=prefix+"-third";send(user,rejected,"第三則","user","active");}
            final String rejectedId=rejected;
            await().atMost(Duration.ofSeconds(2)).until(() -> jdbc.queryForObject("SELECT count(*) FROM event_log WHERE webhook_event_id=? AND event='ai.reply.skipped'",Integer.class,rejectedId)==1);
            verifyNoInteractions(line);send(user,rejectedId,"重投遞不得補發","user","active");
            release.countDown();drain();
            int accepted=scenario.equals("expired")?1:2;
            verify(line,times(accepted)).replyMessage(argThat(request -> request.messages().toString().contains("正常虛構回覆")));
            verify(testModel,times(accepted)).generate(any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND in_reply_to_message_id=? AND direction='OUTBOUND'",Integer.class,user,rejectedId)).isZero();
        }finally{release.countDown();}
    }
    @Test void reasoningExhaustionIsLoggedWithActualUsageButNeverAddedAsSuccessfulAiHistory(CapturedOutput logs) throws Exception {
        String user=user();
        doReturn(new ChatResult("","gpt-6-astra",100,1024,25,"INCOMPLETE","max_output_tokens",1024))
                .when(testModel).generate(any());
        var response=send(user,"fictional-reasoning-limit","虛構測試問題","user","active");drain();
        var failed=jdbc.queryForMap("SELECT event,model,input_tokens,output_tokens,duration_ms,error_type,outcome,detail::text FROM event_log WHERE trace_id=? AND event='ai.generate.failed'",
                response.getHeaders().getFirst("X-Trace-Id"));
        assertThat(failed.get("output_tokens")).isEqualTo(1024L);
        var content=mapper.readTree(failed.get("detail").toString()).path("content");
        var example=mapper.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("docs/examples/webhook/ai-generation-incomplete.json")));
        for(String field:java.util.List.of("event","model","error_type","outcome"))
            assertThat(failed.get(field)).isEqualTo(example.path(field).asText());
        for(String field:java.util.List.of("input_tokens","output_tokens","duration_ms"))
            assertThat(((Number)failed.get(field)).longValue()).isEqualTo(example.path(field).asLong());
        assertThat(content).isEqualTo(example.path("detail").path("content"));
        assertThat(content.path("incomplete_reason").asText()).isEqualTo("max_output_tokens");
        assertThat(content.path("reasoning_tokens").asInt()).isEqualTo(1024);
        assertThat(jdbc.queryForObject("SELECT reply_kind FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",String.class,user)).isEqualTo("FALLBACK");
        assertThat(redis.opsForValue().get(key(user,"context-v2"))).doesNotContain("虛構測試問題");
        assertThat(logs).contains("max_output_tokens","reasoning_tokens").doesNotContain("fake-openai-api-key","fake-ai-reply-token","fake-ai-quote-token");
        verify(line).replyMessage(any());verify(testModel).generate(any());
    }
    @Test void normalAiPersistsRealUsagePairsHistoryAndLogsCompleteBusinessDataWithoutCredentials(CapturedOutput logs) throws Exception {
        String user=user();var captured=new CopyOnWriteArrayList<ChatRequest>();
        doAnswer(call -> {var request=call.getArgument(0,ChatRequest.class);captured.add(request);return result("虛構回覆:"+request.userMessage());}).when(testModel).generate(any());
        var first=send(user,"first","完整原始第一則", "user","active");drain();
        var second=send(user,"second","完整原始第二則", "user","active");drain();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).systemPrompt()).contains("小晴","台北").doesNotContain("完整原始第一則","{{current_time}}","{{last_chat_gap}}");
        assertThat(captured.get(1).history()).containsExactly(new ChatRequest.HistoryMessage("user","完整原始第一則"),
                new ChatRequest.HistoryMessage("assistant","虛構回覆:完整原始第一則"));
        assertThat(captured.get(1).history()).noneMatch(message -> message.content().equals("完整原始第二則"));
        var outbound=jdbc.queryForMap("SELECT * FROM chat_message WHERE user_id=? AND in_reply_to_message_id='second' AND direction='OUTBOUND'",user);
        assertThat(outbound.get("reply_kind")).isEqualTo("AI");assertThat(outbound.get("model_name")).isEqualTo("gpt-6.1-sol");
        assertThat(outbound.get("input_tokens")).isEqualTo(123);assertThat(outbound.get("output_tokens")).isEqualTo(45);assertThat(outbound.get("latency_ms")).isEqualTo(7L);
        assertThat(outbound.get("source_type")).isEqualTo("USER");assertThat(outbound.get("conversation_id")).isEqualTo(user);
        String trace=second.getHeaders().getFirst("X-Trace-Id");
        var generated=jdbc.queryForMap("SELECT detail::text,model,input_tokens,output_tokens FROM event_log WHERE trace_id=? AND event='ai.generate.success'",trace);
        assertThat(generated.get("detail").toString()).contains("虛構回覆:完整原始第二則");
        assertThat(jdbc.queryForObject("SELECT detail::text FROM event_log WHERE trace_id=? AND event='ai.generate.requested'",String.class,trace))
                .contains("小晴","完整原始第一則","虛構回覆:完整原始第一則","完整原始第二則");
        verify(line,times(2)).replyMessage(any());verify(line,never()).pushMessage(any(),any());
        String stored=jdbc.queryForList("SELECT detail::text FROM event_log WHERE user_id=?",String.class,user).toString()
                +jdbc.queryForList("SELECT content::text FROM chat_message WHERE user_id=?",String.class,user)
                +jdbc.queryForList("SELECT user_id FROM event_log WHERE user_id=?",String.class,user);
        assertThat(stored).contains("完整原始第一則","虛構回覆:完整原始第二則",user)
                .doesNotContain("fake-ai-flow-secret","fake-ai-flow-token","fake-openai-api-key","fake-ai-reply-token","fake-ai-quote-token");
        assertThat(logs).contains(trace,user,"完整原始第一則","虛構回覆:完整原始第二則")
                .doesNotContain("fake-ai-flow-secret","fake-ai-flow-token","fake-openai-api-key","fake-ai-reply-token","fake-ai-quote-token");
    }
    @ParameterizedTest @ValueSource(strings={"timeout","empty","incomplete","too_long"})
    void invalidGenerationProducesFallbackWithNoFakeModelUsageAndNeverAddsNormalContext(String scenario) throws Exception {
        String user=user();
        if(scenario.equals("timeout"))doThrow(new ModelFailure("timeout")).when(testModel).generate(any());
        else doReturn(scenario.equals("empty")?result(" "):
                scenario.equals("too_long")?result("虛".repeat(5001)):new ChatResult("未完成原文","gpt-6.1-sol",12,34,5,"INCOMPLETE")).when(testModel).generate(any());
        send(user,"invalid-"+scenario,"虛構輸入", "user","active");drain();
        var row=jdbc.queryForMap("SELECT reply_kind,model_name,input_tokens,output_tokens FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",user);
        assertThat(row.get("reply_kind")).isEqualTo("FALLBACK");assertThat(row.get("model_name")).isNull();
        assertThat(row.get("input_tokens")).isNull();assertThat(row.get("output_tokens")).isNull();
        assertThat(redis.opsForValue().get(key(user,"context-v2"))).doesNotContain("未完成原文","虛".repeat(5001));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_log WHERE user_id=? AND event='ai.generate.failed'",Integer.class,user)).isEqualTo(1);
        if(scenario.equals("too_long"))assertThat(jdbc.queryForObject("SELECT detail::text FROM event_log WHERE user_id=? AND event='ai.generate.failed'",String.class,user))
                .contains("虛".repeat(5001));
        verify(line).replyMessage(any());verify(line,never()).pushMessage(any(),any());
    }
    @Test void groupRoomMissingUserAndStandbyDoNotReadPrivateMemoryOrCallModel() throws Exception {
        String user=user();send(user,"group","原有群聊", "group","active");send(user,"room","原有聊天室", "room","active");
        send(null,"missing","沒有用戶", "group","active");send(user,"standby","待命輸入", "user","standby");drain();
        verifyNoInteractions(testModel);verify(memory,never()).load(any(),any(),any(),anyLong(),any(),any());
        verify(line,times(3)).replyMessage(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND line_message_id='standby'",Integer.class,user)).isEqualTo(1);
    }
    @Test void replyUnknownDoesNotSendFallbackOrPersistSuccessfulOutboundOrAssistantContext() throws Exception {
        String user=user();doReturn(result("不能當作已送出")).when(testModel).generate(any());
        when(line.replyMessage(any())).thenReturn(new CompletableFuture<>());
        send(user,"unknown-send","虛構問題", "user","active");drain();
        verify(line).replyMessage(any());verify(line,never()).pushMessage(any(),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",Integer.class,user)).isZero();
        assertThat(redis.opsForValue().get(key(user,"context-v2"))).doesNotContain("不能當作已送出");
    }
    @Test void cacheFailureAfterReplyDoesNotReleaseDedupOrSendAgain() throws Exception {
        String user=user();doThrow(new IllegalStateException("fake-private-redis-password")).when(memory).save(eq(user),any(),any());
        send(user,"cache-failure","虛構訊息", "user","active");drain();
        send(user,"cache-failure","虛構訊息", "user","active");drain();
        verify(line).replyMessage(any());verify(testModel).generate(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=? AND direction='OUTBOUND' AND reply_kind='AI'",Integer.class,user)).isEqualTo(1);
    }
    @Test void historyFaultAndRateLimitUseDistinctPromptsWithoutModelCalls() throws Exception {
        String failed=user();doThrow(new IllegalStateException("fake-private-db-password")).when(memory).load(eq(failed),any(),any(),anyLong(),any(),any());
        send(failed,"dependency","虛構問題", "user","active");drain();
        assertThat(jdbc.queryForObject("SELECT reply_kind FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",String.class,failed)).isEqualTo("SERVICE_UNAVAILABLE");
        String limited=user();redis.opsForValue().set(key(limited,"rate"),"12",Duration.ofSeconds(60));
        send(limited,"rate","虛構問題", "user","active");drain();
        assertThat(jdbc.queryForObject("SELECT reply_kind FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",String.class,limited)).isEqualTo("RATE_LIMITED");
        verifyNoInteractions(testModel);verify(line,times(2)).replyMessage(any());
    }
    @Test void inputTooLongIsClassifiedBeforeCallingModel() throws Exception {
        String user=user();send(user,"large","訊".repeat(4000), "user","active");drain();
        verifyNoInteractions(testModel);
        assertThat(jdbc.queryForObject("SELECT reply_kind FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",String.class,user)).isEqualTo("INPUT_TOO_LONG");
        assertThat(jdbc.queryForObject("SELECT length(content->>'text') FROM chat_message WHERE user_id=? AND direction='INBOUND'",Integer.class,user)).isEqualTo(4000);
    }
    @Test void lateModelResultIsIgnoredAndOnlyBudgetedFallbackCanBeSent() throws Exception {
        String user=user();doReturn(Duration.ofMillis(600)).when(aiProperties).totalTimeout();
        doReturn(Duration.ofMillis(150)).when(aiProperties).sendReserve();
        doAnswer(call -> {
            var request=call.getArgument(0,ChatRequest.class);
            new CountDownLatch(1).await(request.deadline().remaining().plusMillis(10).toNanos(),TimeUnit.NANOSECONDS);
            return result("過期結果不能送出");
        }).when(testModel).generate(any());
        send(user,"late-model","虛構問題","user","active");drain();
        verify(line).replyMessage(argThat(request -> request.messages().toString().contains(aiProperties.fallback())));
        assertThat(jdbc.queryForObject("SELECT reply_kind FROM chat_message WHERE user_id=? AND direction='OUTBOUND'",String.class,user)).isEqualTo("FALLBACK");
        assertThat(redis.opsForValue().get(key(user,"context-v2"))).doesNotContain("過期結果不能送出");
    }
    @Test void deletionCancelsPendingGenerationWaitsInFlightAndOldResultCannotWriteBack() throws Exception {
        String user=user();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();awaitActualExit(release);return result("刪除前舊生成");}).when(testModel).generate(any());
        var maintenance=Executors.newSingleThreadExecutor();
        try{
            send(user,"in-flight","原始訊息", "user","active");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            send(user,"queued-before-delete","待處理訊息", "user","active");
            await().atMost(Duration.ofSeconds(3)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);
            Future<?> deleted=maintenance.submit(() -> deletion.deleteAll(user));
            await().atMost(Duration.ofSeconds(3)).until(() -> tracker.blocked(user));assertThat(deleted.isDone()).isFalse();
            release.countDown();deleted.get(5,TimeUnit.SECONDS);drain();
            verify(testModel).generate(any());verifyNoInteractions(line);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isZero();
            assertThat(redis.hasKey(key(user,"context-v2"))).isFalse();assertThat(redis.hasKey(key(user,"rate"))).isFalse();
            assertThat(jdbc.queryForObject("SELECT outcome FROM audit_log WHERE target_user_id=? AND event='user.data.deleted'",String.class,user)).isEqualTo("SUCCESS");
            doReturn(result("新的歷史")).when(testModel).generate(any());
            send(user,"new-after-delete","新訊息", "user","active");drain();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id=?",Integer.class,user)).isEqualTo(2);
            assertThat(redis.opsForValue().get(key(user,"context-v2"))).contains("新的歷史").doesNotContain("刪除前舊生成");
        }finally{release.countDown();maintenance.shutdownNow();}
    }
    private void awaitActualExit(CountDownLatch release){boolean interrupted=false;
        try{while(true)try{assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return;}catch(InterruptedException failure){interrupted=true;}}
        finally{if(interrupted)Thread.currentThread().interrupt();}}
    private ResponseEntity<String> send(String user,String id,String text,String source,String mode) throws Exception {
        var root=mapper.createObjectNode();root.put("destination","Ufictional-bot");var event=root.putArray("events").addObject();
        event.put("type","message").put("webhookEventId",id).put("mode",mode).put("timestamp",Instant.now().toEpochMilli()).put("replyToken","fake-ai-reply-token");
        event.putObject("deliveryContext").put("isRedelivery",false);
        var origin=event.putObject("source").put("type",source);if(user!=null)origin.put("userId",user);
        if(source.equals("group"))origin.put("groupId","Caaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        if(source.equals("room"))origin.put("roomId","Raaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        event.putObject("message").put("type","text").put("id",id).put("text",text).put("quoteToken","fake-ai-quote-token");
        byte[] body=mapper.writeValueAsBytes(root);var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("fake-ai-flow-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Line-Signature",Base64.getEncoder().encodeToString(mac.doFinal(body)));
        var response=http.postForEntity("/line/webhook",new HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return response;
    }
    private void drain(){
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookAdmissionExecutor.getActiveCount()==0 && webhookAdmissionExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(5)).until(() -> webhookExecutor.getActiveCount()==0 && webhookExecutor.getQueueSize()==0);
        awaitAdmissions();await().atMost(Duration.ofSeconds(5)).until(scheduler::isIdle);
        await().atMost(Duration.ofSeconds(5)).until(() -> persistenceExecutor.getActiveCount()==0 && persistenceExecutor.getQueueSize()==0);
        await().atMost(Duration.ofSeconds(5)).until(recorder::isIdle);
    }
}
