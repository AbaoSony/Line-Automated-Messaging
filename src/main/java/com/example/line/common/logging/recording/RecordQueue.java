package com.example.line.common.logging.recording;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogEvents;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/** 独立后台线程承接有界日志队列；业务线程只 offer，批写重试一次后输出安全 JSON 兜底。 */
public final class RecordQueue implements AutoCloseable {
    private static final int MAX_WRITE_ATTEMPTS = 2;
    private final ArrayBlockingQueue<RecordedEntry> queue;
    private final LogRecordStore store;
    private final RecorderProperties properties;
    private final MeterRegistry meters;
    private final Thread worker;
    private final com.example.line.common.execution.UserWorkTracker tracker;
    private final java.util.concurrent.ConcurrentMap<UUID,com.example.line.common.execution.UserWorkTracker.Lease> leases=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean accepting = true;
    private volatile int inFlight;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 创建独立记录线程与队列指标，构造时不访问数据库。
     * @param store 幂等批量存储实现
     * @param properties 队列容量、批大小与刷新间隔
     * @param meters 丢弃与失败指标注册表
     */
    public RecordQueue(LogRecordStore store, RecorderProperties properties, MeterRegistry meters) {
        this(store,properties,meters,new com.example.line.common.execution.UserWorkTracker());
    }

    /**
     * 接入统一用户删除跟踪，使已入队日志在数据库删除前完成。
     * @param store 幂等批写入口
     * @param properties 资源配置
     * @param meters 指标
     * @param tracker 用户工作跟踪器
     */
    public RecordQueue(LogRecordStore store,RecorderProperties properties,MeterRegistry meters,
                       com.example.line.common.execution.UserWorkTracker tracker) {
        this.store = store;
        this.properties = properties;
        this.meters = meters;
        this.tracker=tracker;
        queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        meters.gauge("log.queue.size", this, current -> current.queue.size());
        meters.gauge("log.inflight", this, current -> current.inFlight);
        meters.counter("log.dropped");
        worker = new Thread(this::consume, "line-log-writer");
        worker.setDaemon(true);
    }

    /** 启动唯一后台写入线程；仅由 EventRecorder 生命周期调用一次。 */
    public void start() { worker.start(); }

    /**
     * 尝试接收记录，队列满或停机后丢弃并计数，不等待数据库或队列空间。
     * @param audit 是否为敏感操作审计
     * @param fields 已经过白名单过滤的 JSON 快照
     * @return 已接收返回 true，丢弃返回 false
     */
    public boolean offer(boolean audit, Map<String, Object> fields) {
        var entry = new RecordedEntry(UUID.randomUUID(), audit, fields);
        boolean deletion=LogEvents.USER_DATA_DELETED.equals(fields.get("event"));
        var lease=deletion ? null : tracker.register((String)fields.get("user_id"));
        if(!deletion && lease==null) {
            meters.counter("log.dropped").increment();
            if(audit)reportFailure(entry,"user_work_blocked",null);
            return false;
        }
        if(lease!=null)leases.put(entry.id(),lease);
        boolean accepted;
        // 只保护停机边界与内存 offer，锁内禁止 I/O，保证停机刷队列不漏掉最后一个生产者。
        synchronized (queue) {
            pending.incrementAndGet();
            accepted = accepting && queue.offer(entry);
            if (!accepted) pending.decrementAndGet();
        }
        if (!accepted) {
            var removed=leases.remove(entry.id());if(removed!=null)removed.close();
            meters.counter("log.dropped").increment();
            if (audit) reportFailure(entry, "queue_unavailable", null);
        }
        return accepted;
    }

    /**
     * 判断等待和写入是否全部结束，供集成验证或显式维护窗口使用。
     * @return 无排队与执行中记录时返回 true
     */
    public boolean isIdle() { return pending.get() == 0; }

    /** 停止接收并等待全部队列写入或完成 stdout 兜底，不提前关闭独立连接池。 */
    @Override
    public void close() {
        synchronized (queue) { accepting = false; }
        // 不打断进行中的 JDBC 操作；最多等待一个可配置的轮询间隔后继续刷剩余队列。
        boolean interrupted = false;
        while (worker.isAlive()) {
            try { worker.join(); }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void consume() {
        while (accepting || !queue.isEmpty()) {
            var batch = new ArrayList<RecordedEntry>(properties.batchSize());
            try {
                RecordedEntry first = queue.poll(properties.flushInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, properties.batchSize() - 1);
                inFlight = batch.size();
                write(List.copyOf(batch));
            } catch (InterruptedException failure) {
                // 后台线程没有对外中断协议；继续排空，不能因中断丢弃已接收的记录。
            } finally {
                for(var entry:batch){var lease=leases.remove(entry.id());if(lease!=null)lease.close();}
                pending.addAndGet(-batch.size());
                inFlight = 0;
            }
        }
    }

    private void write(List<RecordedEntry> batch) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
            try { store.write(batch); return; }
            catch (RuntimeException failure) { last = failure; }
        }
        for (RecordedEntry entry : batch) {
            // stdout 原始事件一直存在；再次输出白名单 JSON，明确表示该记录未能入库。
            var fallback = new java.util.LinkedHashMap<String, Object>(entry.data());
            fallback.put("record_id", entry.id().toString());
            fallback.put("persistence_fallback", true);
            System.out.println(json(fallback));
            reportFailure(entry, "write_failed_after_retry", last);
        }
    }

    private void reportFailure(RecordedEntry entry, String reason, RuntimeException failure) {
        String operation = entry.audit() ? "audit_log" : "event_log";
        meters.counter("persist.failed", "operation", operation).increment();
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            for (String key : List.of("trace_id", "user_id", "webhook_event_id")) {
                Object value = entry.data().get(key);
                if (value != null) MDC.put(key, value.toString());
            }
            var fields = BusinessLog.fields("operation", operation, "outcome", "FAILED", "reason", reason);
            if (failure == null) BusinessLog.write(LoggerFactory.getLogger(RecordQueue.class), Level.ERROR, LogEvents.PERSIST_FAILED,
                    "Log record persistence failed", fields);
            else BusinessLog.failure(LoggerFactory.getLogger(RecordQueue.class), Level.ERROR, LogEvents.PERSIST_FAILED,
                    "Log record persistence failed", failure, fields);
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }

    private static String json(Map<String, Object> fields) {
        try { return ModelObjectMapper.createNewObjectMapper().writeValueAsString(fields); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid safe log record", failure); }
    }
}
