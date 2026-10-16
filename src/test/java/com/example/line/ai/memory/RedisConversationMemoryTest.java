package com.example.line.ai.memory;

import com.example.line.ai.AiProperties;
import com.example.line.chat.*;
import com.example.line.common.execution.Deadline;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.data.redis.core.*;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Redis 写失败时只保留已接受完整轮，当前及未来成员仍不能混入上下文。 */
class RedisConversationMemoryTest {
    @Test void acceptedRoundBridgesDatabaseLagEvenWhenCacheSaveFailsAndNoTokenOrDraftIsStored() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var history=mock(ChatHistoryService.class);var mapper=new ObjectMapper().findAndRegisterModules();
        var env=new MockEnvironment().withProperty("line.ai.memory.namespace","fictional-memory-unit");
        var props=Binder.get(env).bindOrCreate("line.ai.memory",MemoryProperties.class);
        var memory=new RedisConversationMemory(redis,props,history,mapper,Binder.get(env).bindOrCreate("line.ai",AiProperties.class));
        String user="Uxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx6";Instant base=Instant.parse("2026-10-16T00:00:00Z");
        var members=List.of(new PrivateHistory.Member("first","event1","完整首條",base,base,1,"trace1"),
                new PrivateHistory.Member("second","event2","完整第二條",base,base,2,"trace2"));
        var accepted=new PrivateHistory.Turn(UUID.randomUUID(),members,"唯一已接受回覆");
        memory.rememberAccepted(user,accepted);
        when(values.get(props.namespace()+":{"+user+"}:lock")).thenThrow(new IllegalStateException("fictional cache failure"));
        assertThatThrownBy(() -> memory.save(user,"owner",new PrivateHistory(List.of(accepted),base))).isInstanceOf(IllegalStateException.class);
        when(values.get(props.namespace()+":{"+user+"}:context-v2")).thenReturn(mapper.writeValueAsString(new PrivateHistory(List.of(),null)));
        var next=memory.load(user,"next",base.plusSeconds(1),3,"owner",Deadline.current(Duration.ofSeconds(5)));
        assertThat(next.turns()).containsExactly(accepted);
        assertThat(memory.load(user,"second",base.plusSeconds(1),3,"owner",Deadline.current(Duration.ofSeconds(5))).turns()).isEmpty();
        verify(history,never()).findPrivate(anyString(),anyString(),any(),anyLong(),anyInt());
    }
}
