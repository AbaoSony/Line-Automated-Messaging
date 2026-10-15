package com.example.line.audit;

import com.example.line.common.logging.EventRecorder;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** 将审计记录交给 EventRecorder 的独立日志队列，禁止在敏感操作线程等待数据库。 */
@Repository
class AuditRepository {
    private final EventRecorder recorder;

    AuditRepository(EventRecorder recorder) { this.recorder = recorder; }

    void record(Map<String, ?> fields) { recorder.audit(fields); }
}
