package com.example.line.ai.generation;

import com.example.line.chat.PrivateHistory;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** 验证负责人原始人设占位符、角色边界与完整轮裁剪，不依赖真实模型或系统当前时间。 */
class GenerationPolicyTest {
    static ModelProperties settings(int input) {
        return new ModelProperties("gpt-6.1-sol","low",null,input,1024,Duration.ofSeconds(8),Duration.ofSeconds(4),Duration.ofSeconds(1),Duration.ofMillis(1));
    }
    @Test void personaUsesTaipeiTimeAndDistinguishesFirstConversationFromReadFailure() {
        Instant current=Instant.parse("2026-10-14T13:30:00Z");
        var loader=new PersonaLoader("時間={{current_time}}；間隔={{last_chat_gap}}",Clock.fixed(current,ZoneOffset.UTC));
        assertThat(loader.render(current.minusSeconds(10800),current,false)).contains("2026年10月14日 星期三 晚上 9:30","約 3 小時");
        assertThat(loader.render(null,current,false)).contains("這是第一次聊天");
        assertThat(loader.render(null,current,true)).contains("上次聊天時間暫不可用").doesNotContain("第一次");
        assertThat(loader.render(current.plusSeconds(10),current,false)).contains("不到 1 分鐘");
        assertThatThrownBy(() -> new PersonaLoader(" ",Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void tokenBudgetDropsOnlyOldCompleteTurnsAndKeepsOriginalRoles() {
        var older=new PrivateHistory.Turn("old","old","舊".repeat(100),"舊回覆".repeat(100),Instant.EPOCH,Instant.EPOCH,1);
        var recent=new PrivateHistory.Turn("recent","recent","hi","hello",Instant.EPOCH,Instant.EPOCH,2);
        var composer=new ContextComposer(settings(400));
        var messages=composer.compose("system","current",List.of(older,recent));
        assertThat(messages).containsExactly(new ChatRequest.HistoryMessage("user","hi"),new ChatRequest.HistoryMessage("assistant","hello"));
        assertThat(older.userText()).hasSize(100);
        assertThat(messages).noneMatch(message -> message.role().equals("system") || message.content().equals("current"));
    }
    @Test void oversizedCurrentInputAndUnsupportedParametersFailBeforeApiCall() {
        assertThatThrownBy(() -> new ContextComposer(settings(200)).compose("system","x".repeat(1000),List.of()))
                .isInstanceOf(ModelFailure.class).hasMessage("input_too_long");
        assertThatThrownBy(() -> new ModelProperties("gpt-6.1-sol","none",null,8192,1024,Duration.ofSeconds(8),Duration.ofSeconds(4),Duration.ofSeconds(1),Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelProperties("gpt-6.1-sol","low",0.2,8192,1024,Duration.ofSeconds(8),Duration.ofSeconds(4),Duration.ofSeconds(1),Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void multiMemberBudgetChargesEachUserBoundaryAndNeverDropsCurrentMembersToFit() {
        var current=List.of("第一則完整原文","第二則完整原文");
        assertThatThrownBy(() -> new ContextComposer(settings(200)).composeMembers("system",current,List.of()))
                .isInstanceOf(ModelFailure.class).hasMessage("input_too_long");
        assertThat(current).containsExactly("第一則完整原文","第二則完整原文");
        var old=new PrivateHistory.Turn("old","old","舊".repeat(100),"舊回覆",Instant.EPOCH,Instant.EPOCH,1);
        assertThat(new ContextComposer(settings(300)).composeMembers("system",current,List.of(old))).isEmpty();
    }
    @Test void aggregatedHistoryKeepsEveryUserRoleAndNeverTrimsPartOfAnOldRound() {
        var members=List.of(new PrivateHistory.Member("first","event1","完整第一條",Instant.EPOCH,Instant.EPOCH,1,"trace1"),
                new PrivateHistory.Member("second","event2","完整第二條",Instant.EPOCH,Instant.EPOCH,2,"trace2"));
        var turn=new PrivateHistory.Turn(java.util.UUID.randomUUID(),members,"唯一完整回覆");
        assertThat(new ContextComposer(settings(1000)).composeMembers("system",List.of("current"),List.of(turn)))
                .containsExactly(new ChatRequest.HistoryMessage("user","完整第一條"),new ChatRequest.HistoryMessage("user","完整第二條"),
                        new ChatRequest.HistoryMessage("assistant","唯一完整回覆"));
        assertThat(new ContextComposer(settings(350)).composeMembers("system",List.of("current"),List.of(turn))).isEmpty();
        assertThat(turn.members()).hasSize(2);
    }
}
