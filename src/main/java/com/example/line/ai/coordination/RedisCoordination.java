package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.ai.memory.MemoryProperties;
import com.example.line.common.execution.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 用 Redis 原子限流和短期所有权保护缓存，锁不能替代单实例的接收顺序调度。 */
@Component
public class RedisCoordination {
    private static final Duration POLL_INTERVAL=Duration.ofMillis(10);
    private static final Duration COMMIT_MARGIN=Duration.ofSeconds(1);
    private static final DefaultRedisScript<Long> RATE=new DefaultRedisScript<>("""
            local n=redis.call('incr',KEYS[1]); if n == 1 then redis.call('pexpire',KEYS[1],ARGV[1]) end
            return n
            """,Long.class);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("""
            if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) end return 0
            """,Long.class);
    private final StringRedisTemplate redis;
    private final MemoryProperties memory;
    private final AiProperties ai;
    /**
     * 注入 Redis 和有界等待策略。
     * @param redis 命令超时受限的 Redis 客户端
     * @param memory 命名空间与限流窗口
     * @param ai 统一预算及锁等待上限
     */
    public RedisCoordination(StringRedisTemplate redis,MemoryProperties memory,AiProperties ai) { this.redis=redis;this.memory=memory;this.ai=ai; }
    /**
     * 原子递增并设置首次 TTL，重复事件由 Webhook 去重后才调用。
     * @param user 完整用户
     * @return 当前窗口未超阈值时为 true
     */
    public boolean allow(String user) {
        Long count=redis.execute(RATE,List.of(key(user,"rate")),Long.toString(memory.rateWindow().toMillis()));
        if (count == null) throw new IllegalStateException("Rate counter unavailable");
        return count<=memory.rateLimit();
    }
    /**
     * 在统一剩余预算内取得短期所有权，TTL 覆盖剩余处理与提交余量。
     * @param user 完整用户
     * @param deadline 入口截止时间
     * @return 唯一持有者，等待到期时为空
     * @throws IllegalStateException 被中断时抛出并恢复中断标记
     */
    public String acquire(String user,Deadline deadline) {
        String owner=UUID.randomUUID().toString();
        long end=System.nanoTime()+deadline.limit(ai.lockWait()).toNanos();
        do {
            if (!deadline.permits(ai.sendReserve())) return null;
            if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(user,"lock"),owner,deadline.remaining().plus(COMMIT_MARGIN)))) return owner;
            if(System.nanoTime()>=end) return null;
            try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(POLL_INTERVAL.toNanos(),Math.max(0,end-System.nanoTime()))); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException("Lock wait interrupted",failure); }
        } while(System.nanoTime()<end);
        return null;
    }
    /**
     * 在发送前验证所有权，远端发送在途时仍可能遇到网络分区，不能宣称跨服务原子性。
     * @param user 完整用户
     * @param owner 当前唯一持有者
     * @return 所有权仍有效时为 true
     */
    public boolean owns(String user,String owner) { return owner.equals(redis.opsForValue().get(key(user,"lock"))); }
    /**
     * 原子释放自己的锁，绝不直接删除其他持有者。
     * @param user 完整用户
     * @param owner 当前持有者
     */
    public void release(String user,String owner) { redis.execute(RELEASE,List.of(key(user,"lock")),owner); }
    private String key(String user,String suffix) { return memory.namespace()+":{"+user+"}:"+suffix; }
}
