package com.example.line.common.execution;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 统一跟踪用户生成、发送及异步写入；删除屏障在事务外等待，短期墓碑拒绝删除前收到的旧工作。 */
@Component
public class UserWorkTracker {
    private static final int MAX_TRACKED_USERS = 4096;
    // 墓碑覆盖最长 50 秒入口预算及清理余量，避免放宽模型等待后旧请求重新进入。
    private static final Duration TOMBSTONE_TTL = Duration.ofSeconds(75);
    private final Map<String, Slot> slots = new HashMap<>();
    private final java.util.function.LongSupplier clock;
    private long epoch;

    /** 使用单调时钟跟踪在途工作和删除后的旧请求保护。 */
    public UserWorkTracker() { this(System::nanoTime); }

    UserWorkTracker(java.util.function.LongSupplier clock) { this.clock=clock; }

    /**
     * 登记当前短时工作；删除中的用户或旧请求不能产生新的写入。
     * @param userId 完整用户标识，无用户任务允许为空
     * @return 工作租约，拒绝时返回 null
     */
    public synchronized Lease register(String userId) {
        if (userId == null) return new Lease(null, null);
        prune();
        Slot slot = slots.get(userId);
        if (slot == null) {
            if (slots.size() >= MAX_TRACKED_USERS) return null;
            slot = new Slot(++epoch);
            slots.put(userId, slot);
        }
        String received = MDC.get("received_nanos");
        long receipt = received == null ? clock.getAsLong() : Long.parseLong(received);
        if (slot.blocked || slot.deleted && receipt < slot.deletedAt) return null;
        slot.pending++;
        return new Lease(userId, slot);
    }

    /**
     * 阻止新工作进入，调用者在关闭屏障前必须完成删除或明确失败。
     * @param userId 完整用户标识
     * @return 事务外屏障
     * @throws IllegalStateException 已有删除或容量不足时抛出
     */
    public synchronized Barrier block(String userId) {
        prune();
        if (!slots.containsKey(userId) && slots.size() >= MAX_TRACKED_USERS) throw new IllegalStateException("User tracker capacity exceeded");
        Slot slot = slots.computeIfAbsent(userId, ignored -> new Slot(++epoch));
        if (slot.blocked) throw new IllegalStateException("User deletion already in progress");
        slot.blocked = true;
        return new Barrier(userId, slot);
    }

    /**
     * 检查生成任务是否已进入删除状态，发送前和写回前必须再次检查。
     * @param userId 完整用户标识
     * @return 正在删除时返回 true
     */
    public synchronized boolean blocked(String userId) { return slots.containsKey(userId) && slots.get(userId).blocked; }

    /**
     * 只在有效私聊准入时绑定本地取消代次，随 MDC 传入模型和技术提示，不保存为日志字段。
     * @param userId 已登记短时生命周期租约的用户
     * @throws IllegalStateException 缺少工作登记时抛出
     */
    public synchronized void bindAiAdmission(String userId){
        Slot slot=slots.get(userId);
        if(slot==null)throw new IllegalStateException("AI admission has no work lease");
        MDC.put("ai_work_epoch",Long.toString(slot.epoch));
    }
    /**
     * 有效取消关注使旧正文和排队技术提示失效，不阻止其原文和真实发送结果落库。
     * @param userId 当前生命周期工作已登记的用户
     */
    public synchronized void cancelAi(String userId){var slot=slots.get(userId);if(slot!=null)slot.epoch=++epoch;}
    /**
     * 原子核对私聊取消代次并登记发送；已经登记的在途发送继续真实收尾。
     * @param userId 完整用户标识，非 AI 发送不带代次
     * @return 有效发送租约；旧代次、删除或容量拒绝时为空
     */
    public synchronized Lease registerSending(String userId){
        String captured=MDC.get("ai_work_epoch");Slot slot=slots.get(userId);
        if(captured!=null && (slot==null || slot.epoch!=Long.parseLong(captured)))return null;
        return register(userId);
    }

    /**
     * 登记本次删除结束后产生的事件/审计，允许越过自己的屏障，但必须被下一次删除等待。
     * @param userId 完整删除目标；未知目标允许为空
     * @return 有界记录租约，容量不足时为空
     */
    public synchronized Lease registerDeletionRecord(String userId){
        if(userId==null)return new Lease(null,null);
        prune();Slot slot=slots.get(userId);
        if(slot==null){if(slots.size()>=MAX_TRACKED_USERS)return null;slot=new Slot(++epoch);slots.put(userId,slot);}
        slot.pending++;return new Lease(userId,slot);
    }

    /**
     * 上游全部停止后等待实际生成、发送、业务写入及日志工作，不把 Future 取消当成排空。
     * @param timeout 停机等待上限
     * @throws IllegalStateException 仍有在途工作或被中断时抛出
     */
    public synchronized void awaitAll(Duration timeout) {
        long end=clock.getAsLong()+timeout.toNanos();
        while(slots.values().stream().anyMatch(slot -> slot.pending>0)) {
            long left=end-clock.getAsLong();
            if(left<=0)throw new IllegalStateException("User work shutdown did not drain");
            try{java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this,left);}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("User work shutdown interrupted");}
        }
    }

    private void prune() {
        long cutoff = clock.getAsLong() - TOMBSTONE_TTL.toNanos();
        slots.entrySet().removeIf(entry -> entry.getValue().pending == 0 && !entry.getValue().blocked
                && (!entry.getValue().deleted || entry.getValue().deletedAt < cutoff));
    }

    private static final class Slot {
        int pending;boolean blocked,deleted;long deletedAt,epoch;
        Slot(long epoch){this.epoch=epoch;}
    }

    /** 当前工作的计数租约，完成或拒绝后必须关闭。 */
    public final class Lease implements AutoCloseable {
        private final String user;
        private final Slot slot;
        private boolean closed;
        private Lease(String user, Slot slot) { this.user = user; this.slot = slot; }
        /**
         * 延续已准入工作的实际传输清理，不把删除后新任务伪装成旧任务。
         * @return 与当前工作同代次的独立子租约
         * @throws IllegalStateException 当前租约已经关闭时抛出
         */
        public Lease retain() {
            synchronized(UserWorkTracker.this) {
                if(closed)throw new IllegalStateException("User work lease already closed");
                if(slot!=null)slot.pending++;
                return new Lease(user,slot);
            }
        }
        /** 释放当前工作，唤醒等待中的删除；重复关闭安全。 */
        @Override public void close() {
            synchronized (UserWorkTracker.this) {
                if (closed) return;
                closed = true;
                if (slot != null) slot.pending--;
                UserWorkTracker.this.notifyAll();
                prune();
            }
        }
    }

    /** 删除期间持有的本地屏障，不包含数据库事务。 */
    public final class Barrier implements AutoCloseable {
        private final String user;
        private final Slot slot;
        private Barrier(String user, Slot slot) { this.user = user; this.slot = slot; }
        /**
         * 在限定时间等待已有生成、发送和写入结束。
         * @param timeout 等待预算
         * @throws IllegalStateException 超时或被中断时抛出，不假报删除成功
         */
        public void await(Duration timeout) {
            long end = clock.getAsLong() + timeout.toNanos();
            synchronized (UserWorkTracker.this) {
                while (slot.pending > 0) {
                    long left = end - clock.getAsLong();
                    if (left <= 0) throw new IllegalStateException("User deletion wait timed out");
                    try { java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(UserWorkTracker.this, left); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("User deletion interrupted", failure);
                    }
                }
            }
        }
        /** 标记删除提交，旧请求在其入口截止时间内也不能重新创建历史。 */
        public void committed() { synchronized (UserWorkTracker.this) { slot.deletedAt = clock.getAsLong(); slot.deleted=true; } }
        /** 解除屏障；删除失败时保留原有数据与工作结果，允许重试。 */
        @Override public void close() { synchronized (UserWorkTracker.this) { slot.blocked = false; prune(); } }
    }
}
