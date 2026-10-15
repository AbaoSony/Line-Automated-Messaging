package com.example.line.common.logging.recording;

import java.util.List;

/** 为有界队列抽象批量写入，使重试、降级与停机行为可以脱离真实数据库验证。 */
@FunctionalInterface
public interface LogRecordStore {
    /**
     * 持久化一个批次，重复 UUID 必须安全忽略。
     * @param entries 已过滤的事件或审计记录
     * @throws RuntimeException 数据库写入失败时抛出，由队列有限重试
     */
    void write(List<RecordedEntry> entries);
}
