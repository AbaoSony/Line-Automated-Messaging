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
    private static final Duration TOMBSTONE_TTL = Duration.ofSeconds(30);
    private final Map<String, Slot> slots = new HashMap<>();

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
            slot = new Slot();
            slots.put(userId, slot);
        }
        String received = MDC.get("received_nanos");
        long receipt = received == null ? System.nanoTime() : Long.parseLong(received);
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
        Slot slot = slots.computeIfAbsent(userId, ignored -> new Slot());
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

    private void prune() {
        long cutoff = System.nanoTime() - TOMBSTONE_TTL.toNanos();
        slots.entrySet().removeIf(entry -> entry.getValue().pending == 0 && !entry.getValue().blocked
                && (!entry.getValue().deleted || entry.getValue().deletedAt < cutoff));
    }

    private static final class Slot { int pending; boolean blocked,deleted; long deletedAt; }

    /** 当前工作的计数租约，完成或拒绝后必须关闭。 */
    public final class Lease implements AutoCloseable {
        private final String user;
        private final Slot slot;
        private boolean closed;
        private Lease(String user, Slot slot) { this.user = user; this.slot = slot; }
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
            long end = System.nanoTime() + timeout.toNanos();
            synchronized (UserWorkTracker.this) {
                while (slot.pending > 0) {
                    long left = end - System.nanoTime();
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
        public void committed() { synchronized (UserWorkTracker.this) { slot.deletedAt = System.nanoTime(); slot.deleted=true; } }
        /** 解除屏障；删除失败时保留原有数据与工作结果，允许重试。 */
        @Override public void close() { synchronized (UserWorkTracker.this) { slot.blocked = false; prune(); } }
    }
}
