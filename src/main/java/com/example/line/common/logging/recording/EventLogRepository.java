package com.example.line.common.logging.recording;

import com.example.line.common.logging.LogData;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 使用独立日志连接池写入事件和审计；仅白名单字段进入 jsonb，失败交由 RecordQueue 兜底。 */
@Repository
public class EventLogRepository implements LogRecordStore {
    private static final List<String> EVENT_COLUMNS = List.of("ts", "event", "level", "trace_id", "webhook_event_id",
            "user_id", "outcome", "duration_ms", "http_status", "error_type", "message_type", "model",
            "input_tokens", "output_tokens", "service", "env", "version", "instance");
    private static final String EVENT_SQL = "INSERT INTO event_log (id, " + String.join(",", EVENT_COLUMNS)
            + ",detail) VALUES (?, ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (id) DO NOTHING";
    private static final String AUDIT_SQL = "INSERT INTO audit_log (id,event,operator,source_ip,target_user_id,trace_id,"
            + "outcome,detail,created_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (id) DO NOTHING";
    private final JdbcTemplate jdbc;

    /**
     * 注入日志专属数据源；不注册第二个 JdbcTemplate Bean，以免覆盖业务 JDBC 自动配置。
     * @param dataSource 独立日志连接池
     * @param properties SQL 执行超时
     */
    public EventLogRepository(@Qualifier("logDataSource") DataSource dataSource, RecorderProperties properties) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(properties.queryTimeoutSeconds());
    }

    /**
     * 分别批写事件和审计，重试保持 UUID 幂等；不传播原始异常消息到日志。
     * @param entries 已过滤的不可变记录
     * @throws org.springframework.dao.DataAccessException 数据库写入失败时抛出
     */
    @Override
    public void write(List<RecordedEntry> entries) {
        var events = entries.stream().filter(entry -> !entry.audit()).map(this::eventArguments).toList();
        var audits = entries.stream().filter(RecordedEntry::audit).map(this::auditArguments).toList();
        if (!events.isEmpty()) jdbc.batchUpdate(EVENT_SQL, events);
        if (!audits.isEmpty()) jdbc.batchUpdate(AUDIT_SQL, audits);
    }

    /**
     * 分批删除过期事件，只有配置清理任务显式启用时才调用。
     * @param cutoff 不包含边界时刻的过期截止时间
     * @param limit 最大删除条数
     * @return 实际删除数
     */
    public int cleanEvents(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM event_log WHERE id IN (SELECT id FROM event_log WHERE ts < ? ORDER BY ts LIMIT ?)",
                Timestamp.from(cutoff), limit);
    }

    /**
     * 分批删除过期审计，与事件保留期相互独立。
     * @param cutoff 不包含边界时刻的截止时间
     * @param limit 最大删除条数
     * @return 实际删除数
     */
    public int cleanAudits(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log WHERE created_at < ? ORDER BY created_at LIMIT ?)",
                Timestamp.from(cutoff), limit);
    }

    private Object[] eventArguments(RecordedEntry entry) {
        var args = new java.util.ArrayList<Object>();
        args.add(entry.id());
        for (String column : EVENT_COLUMNS) args.add(column.equals("ts")
                ? Timestamp.from(Instant.parse((String) entry.data().get(column))) : entry.data().get(column));
        args.add(json(entry.data().get("detail")));
        return args.toArray();
    }

    private Object[] auditArguments(RecordedEntry entry) {
        Map<String, Object> data = entry.data();
        return new Object[] {entry.id(), data.get("event"), data.get("operator"), data.get("source_ip"),
                data.get("target_user_id"), data.get("trace_id"), data.get("outcome"), json(data.get("detail")),
                Timestamp.from(Instant.parse((String) data.get("ts")))};
    }

    private static String json(Object value) {
        try { return ModelObjectMapper.createNewObjectMapper().writeValueAsString(LogData.business(value)); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid log JSON", failure); }
    }
}
