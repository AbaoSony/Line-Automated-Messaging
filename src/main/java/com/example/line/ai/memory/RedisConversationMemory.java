package com.example.line.ai.memory;

import com.example.line.ai.AiProperties;
import com.example.line.chat.ChatHistoryService;
import com.example.line.chat.PrivateHistory;
import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogEvents;
import com.example.line.user.UserDataPurger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 合并已确认串行快照与 Redis/数据库历史，缓存可丢弃；提交和删除不抢占他人的锁。 */
@Component
public class RedisConversationMemory implements ConversationMemory, UserDataPurger {
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('psetex',KEYS[2],ARGV[2],ARGV[3]); return 1
            """,Long.class);
    private static final DefaultRedisScript<Long> PURGE = new DefaultRedisScript<>("""
            if redis.call('exists',KEYS[1]) == 1 then return 0 end
            redis.call('del',KEYS[2],KEYS[3]); return 1
            """,Long.class);
    private final StringRedisTemplate redis;
    private final MemoryProperties properties;
    private final ChatHistoryService history;
    private final ObjectMapper mapper;
    private final int maxUsers;
    private final LinkedHashMap<String,Local> local = new LinkedHashMap<>(16,0.75f,true);

    /**
     * 注入热缓存、权威历史与 JSON 序列化工具。
     * @param redis Redis 客户端
     * @param properties 缓存边界
     * @param history P0 历史查询能力
     * @param mapper JSON 工具
     * @param ai 本地会话容量
     */
    public RedisConversationMemory(StringRedisTemplate redis, MemoryProperties properties, ChatHistoryService history,
                                   ObjectMapper mapper, AiProperties ai) {
        this.redis=redis; this.properties=properties; this.history=history; this.mapper=mapper; maxUsers=ai.maxUsers();
    }
    /**
     * 先验证 Redis 可用，再合并本地尚未落库的正常轮，严格按当前边界筛选。
     * @param user 私聊用户
     * @param messageId 当前消息 ID
     * @param received 接收时间
     * @param sequence 同时刻顺序
     * @return 已配对私聊历史
     * @throws RuntimeException 缓存或历史读取失败时抛出，不降级无历史调用
     */
    @Override public PrivateHistory load(String user,String messageId,Instant received,long sequence,String owner,
                                        com.example.line.common.execution.Deadline deadline) {
        String cached = redis.opsForValue().get(key(user,"context"));
        PrivateHistory stored;
        if (cached == null) {
            BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.DEBUG,LogEvents.MEMORY_CACHE_MISS,
                    "Conversation cache miss",Map.of());
            stored=history.findPrivate(user,messageId,received,sequence,properties.scanLimit());
            // 回源只回填已持久化内容；串行持有者随后用原子 SAVE 写入新轮。
            if(!deadline.permits(java.time.Duration.ZERO) || Thread.currentThread().isInterrupted())throw new IllegalStateException("History deadline exceeded");
            Long filled=redis.execute(SAVE,List.of(key(user,"lock"),key(user,"context")),owner,
                    Long.toString(properties.ttl().toMillis()),json(stored));
            if(!Long.valueOf(1).equals(filled))throw new IllegalStateException("History owner lost");
        } else stored=parse(cached);
        Local entry;
        synchronized (local) { entry=local.get(user); }
        var merged=new LinkedHashMap<String,PrivateHistory.Turn>();
        for (var turn:stored.turns()) merged.put(turn.messageId(),turn);
        if (entry != null && entry.touched.plus(properties.ttl()).isAfter(Instant.now()))
            for (var turn:entry.turns) merged.put(turn.messageId(),turn);
        var selected=merged.values().stream().filter(turn -> !turn.messageId().equals(messageId)
                && before(turn.receivedAt(),turn.sequence(),received,sequence))
                .sorted(Comparator.comparing(PrivateHistory.Turn::receivedAt).thenComparingLong(PrivateHistory.Turn::sequence)).toList();
        selected=selected.subList(Math.max(0,selected.size()-properties.rounds()),selected.size());
        // 缓存正常轮次并不包含失败提示、待命或非文本入站，不能据此伪造上次聊天时间。
        var lastStored=history.lastPrivateInbound(user,messageId,received,sequence);
        Instant last=lastStored==null?null:lastStored.occurredAt();
        if (entry != null) {
            synchronized (local) {
                var previous=entry.inputs.stream().filter(stamp -> !stamp.id.equals(messageId)
                        && before(stamp.received,stamp.sequence,received,sequence)).max(Comparator.comparing(Stamp::received).thenComparingLong(Stamp::sequence));
                if (previous.isPresent() && (lastStored==null || before(lastStored.receivedAt(),lastStored.sequence(),previous.get().received,previous.get().sequence))) last=previous.get().occurred;
            }
        }
        return new PrivateHistory(List.copyOf(selected),last);
    }
    /**
     * LINE 已接受后更新有界本地快照，并原子验证 Redis 锁写入缓存；失败不撤销已发送结果。
     * @param user 私聊用户
     * @param owner 锁持有者
     * @param snapshot 已发送轮次
     * @throws RuntimeException Redis 写入失败或锁持有权失效时抛出
     */
    @Override public void save(String user,String owner,PrivateHistory snapshot) {
        var turns=snapshot.turns().subList(Math.max(0,snapshot.turns().size()-properties.rounds()),snapshot.turns().size());
        Long updated=redis.execute(SAVE,List.of(key(user,"lock"),key(user,"context")),owner,
                Long.toString(properties.ttl().toMillis()),json(new PrivateHistory(List.copyOf(turns),snapshot.lastInboundAt())));
        if (!Long.valueOf(1).equals(updated)) throw new IllegalStateException("Conversation lock lost");
        synchronized (local) { entry(user).turns=List.copyOf(turns); }
    }
    /**
     * 保存有界入站时间观察，当前与未来消息在读取时排除。
     * @param user 私聊用户
     * @param id 消息 ID
     * @param occurred LINE 时间
     * @param received 接收时间
     * @param sequence 接收顺序
     */
    @Override public void observe(String user,String id,Instant occurred,Instant received,long sequence) {
        synchronized (local) {
            Local entry=entry(user);
            entry.inputs.removeIf(stamp -> Objects.equals(stamp.id,id));
            entry.inputs.add(new Stamp(id,occurred,received,sequence));
            while(entry.inputs.size()>properties.scanLimit()) entry.inputs.remove(0);
        }
    }
    /**
     * 删除本地与 Redis 用户状态，不直接删除另一持有者的锁。
     * @param userId 完整用户
     * @throws IllegalStateException 仍有锁持有者时抛出，删除可重试
     */
    @Override public void purge(String userId) {
        Long deleted=redis.execute(PURGE,List.of(key(userId,"lock"),key(userId,"context"),key(userId,"rate")));
        if (!Long.valueOf(1).equals(deleted)) throw new IllegalStateException("User still has an active Redis owner");
        synchronized (local) { local.remove(userId); }
    }
    private String key(String user,String suffix) { return properties.namespace()+":{"+user+"}:"+suffix; }
    private Local entry(String user) {
        Local result=local.computeIfAbsent(user,ignored -> new Local()); result.touched=Instant.now();
        while(local.size()>maxUsers) local.remove(local.keySet().iterator().next());
        return result;
    }
    private static boolean before(Instant at,long seq,Instant current,long currentSeq) {
        return at.isBefore(current) || at.equals(current) && seq<currentSeq;
    }
    private String json(PrivateHistory value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException failure) { throw new IllegalStateException("Invalid conversation snapshot",failure); }
    }
    private PrivateHistory parse(String value) {
        try { return mapper.readValue(value,PrivateHistory.class); }
        catch(JsonProcessingException failure) { throw new IllegalStateException("Invalid cached conversation",failure); }
    }
    private static final class Local { List<PrivateHistory.Turn> turns=List.of(); final List<Stamp> inputs=new ArrayList<>(); Instant touched=Instant.now(); }
    private record Stamp(String id,Instant occurred,Instant received,long sequence) {}
}
