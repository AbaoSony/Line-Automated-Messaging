package com.example.line.ai.aggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 对轮次成员及仍被执行快照保留的原文统一记账；共享引用不重复计算原文字数。 */
public final class TurnBuffer {
    private final int maxMembers;
    private final long maxCharacters;
    private int members;
    private long characters;

    /**
     * 创建整个会话调度器共享的缓冲账本，不持有用户会话映射。
     * @param properties 全局成员和字符上限
     * @throws NullPointerException 配置为空时抛出
     */
    public TurnBuffer(AggregationProperties properties) {
        Objects.requireNonNull(properties, "properties");
        maxMembers = properties.maxBufferedMembers();
        maxCharacters = properties.maxBufferedCharacters();
    }

    /**
     * 为一次实际准入保留原文；容量不足时明确拒绝，不等待或驱逐已有成员。
     * @param member 已校验的业务成员
     * @return 成员持有权，容量不足返回 null
     * @throws NullPointerException 成员为空时抛出
     */
    public synchronized Membership retain(InboundMember member) {
        Objects.requireNonNull(member, "member");
        if (members >= maxMembers || member.text().length() > maxCharacters - characters) return null;
        members++;
        characters += member.text().length();
        return new Membership(new Entry(member));
    }

    /**
     * 冻结共享业务快照，并保留所有成员直到执行方实际结束且关闭快照。
     * @param turnId 稳定轮次 ID
     * @param epoch 会话代次
     * @param inputVersion 输入版本
     * @param generationVersion 正文版本
     * @param memberships 仍有效且按接收顺序排列的成员持有权
     * @return 作用域内不可变的业务快照；使用结束必须 close
     * @throws IllegalArgumentException 成员为空、顺序错误、跨用户或账本不匹配时抛出
     * @throws IllegalStateException 成员已释放时抛出
     */
    public synchronized TurnSnapshot snapshot(UUID turnId, long epoch, long inputVersion,
                                                long generationVersion, List<Membership> memberships) {
        Objects.requireNonNull(turnId, "turnId");
        if (memberships == null || memberships.isEmpty()) throw new IllegalArgumentException("Empty turn snapshot");
        var entries = new ArrayList<Entry>();
        String user = null;
        long previous = 0;
        for (var membership : memberships) {
            if (membership == null || membership.owner() != this) throw new IllegalArgumentException("Foreign turn buffer");
            Entry entry = membership.entry;
            if (entry == null) throw new IllegalStateException("Released turn member");
            if (entry.member.sequence() <= previous || user != null && !user.equals(entry.member.userId())) {
                throw new IllegalArgumentException("Unordered or cross-user turn members");
            }
            user = entry.member.userId();
            previous = entry.member.sequence();
            entries.add(entry);
        }
        entries.forEach(entry -> entry.references++);
        List<InboundMember> data = entries.stream().map(entry -> entry.member).toList();
        return new TurnSnapshot(turnId, epoch, inputVersion, generationVersion, data, () -> {
            synchronized (TurnBuffer.this) { entries.forEach(this::release); }
            entries.clear();
        });
    }

    /** @return 活动轮次、待处理轮次及尚未结束快照保留的唯一成员数 */
    public synchronized int members() { return members; }
    /** @return 上述成员原文的 UTF-16 单元总数，不等于 JVM 堆大小 */
    public synchronized long characters() { return characters; }

    private void release(Entry entry) {
        if (--entry.references == 0) {
            members--;
            characters -= entry.member.text().length();
        }
    }

    private static final class Entry {
        final InboundMember member;
        int references = 1;
        Entry(InboundMember member) { this.member = member; }
    }

    /** 一个轮次的成员持有权；快照拥有独立持有权，关闭本对象不代表在途快照释放。 */
    public final class Membership implements AutoCloseable {
        private Entry entry;
        private Membership(Entry entry) { this.entry = entry; }
        private TurnBuffer owner() { return TurnBuffer.this; }
        /**
         * 读取仍被轮次持有的不可变成员。
         * @return 原始业务成员
         * @throws IllegalStateException 本持有权已经关闭时抛出
         */
        public InboundMember member() {
            synchronized (TurnBuffer.this) {
                if (entry == null) throw new IllegalStateException("Released turn member");
                return entry.member;
            }
        }
        /** 释放本轮持有权，重复关闭安全，不提前归还在途快照占用。 */
        @Override public void close() {
            synchronized (TurnBuffer.this) {
                if (entry != null) { release(entry); entry = null; }
            }
        }
    }
}
