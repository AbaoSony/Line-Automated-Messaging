package com.example.line.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.MDC;

/** 关注事件的无凭证排序快照；精度与 PostgreSQL 一致，重投递不争抢同时间的新状态。 */
public record LifecycleOrder(Instant occurredAt, Instant receivedAt, long sequence, String eventId, boolean redelivery) {
    /**
     * 校验并统一数据库精度，不保存 SDK 事件或短效凭证。
     * @param occurredAt LINE 事件时间
     * @param receivedAt 本服务接收时间
     * @param sequence 接收顺序
     * @param eventId 业务事件标识
     * @param redelivery 是否重投递
     * @throws IllegalArgumentException 排序字段缺失时抛出
     */
    public LifecycleOrder {
        if (occurredAt==null || receivedAt==null || sequence<0 || eventId==null || eventId.isBlank())
            throw new IllegalArgumentException("Invalid lifecycle order");
        occurredAt=occurredAt.truncatedTo(ChronoUnit.MICROS);receivedAt=receivedAt.truncatedTo(ChronoUnit.MICROS);
    }
    /**
     * 冻结有序入口的接收边界，不能用异步数据库执行时间替代。
     * @param occurredAt LINE 时间，缺失时使用当前时间
     * @param eventId 事件标识
     * @param redelivery 是否重投递
     * @return 不可变排序边界
     */
    public static LifecycleOrder current(Instant occurredAt,String eventId,boolean redelivery) {
        String at=MDC.get("accepted_at"),seq=MDC.get("received_sequence");
        return new LifecycleOrder(occurredAt==null?Instant.now():occurredAt,at==null?Instant.now():Instant.parse(at),
                seq==null?0:Long.parseLong(seq),eventId,redelivery);
    }
}
