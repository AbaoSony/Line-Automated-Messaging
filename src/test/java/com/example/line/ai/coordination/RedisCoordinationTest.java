package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.ai.memory.MemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 取消后的锁释放属于仍在途的清理，不能被原中断标记跳过后假报用户工作排空。 */
class RedisCoordinationTest {
    @Test void releaseExecutesOwnerCheckedCleanupWithoutCancellationFlagAndRestoresItAfterward() {
        var redis=mock(StringRedisTemplate.class);
        doAnswer(call -> {assertThat(Thread.currentThread().isInterrupted()).isFalse();return 1L;})
                .when(redis).execute(any(RedisScript.class),anyList(),eq("fictional-owner"));
        var env=new MockEnvironment().withProperty("line.ai.memory.namespace","fictional-tests");
        var coordinator=new RedisCoordination(redis,Binder.get(env).bindOrCreate("line.ai.memory",MemoryProperties.class),
                Binder.get(env).bindOrCreate("line.ai",AiProperties.class));
        try {
            Thread.currentThread().interrupt();coordinator.release("Ufictional-release","fictional-owner");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(redis).execute(any(RedisScript.class),anyList(),eq("fictional-owner"));
        } finally {Thread.interrupted();}
    }
}
