package com.example.line.common.logging;

import java.util.Set;

/** 固定事件名登记表，供业务日志与后续 EventRecorder 共用；新增名称需同步 docs/日志规范.md。 */
public final class LogEvents {
    public static final String USER_FOLLOW = "user.follow";
    public static final String USER_UNFOLLOW = "user.unfollow";
    public static final String USER_MESSAGE_RECEIVED = "user.message.received";
    public static final String USER_POSTBACK = "user.postback";
    public static final String USER_RATE_LIMITED = "user.rate_limited";
    public static final String WEBHOOK_RECEIVED = "webhook.received";
    public static final String WEBHOOK_SIGNATURE_INVALID = "webhook.signature_invalid";
    public static final String WEBHOOK_DUPLICATE = "webhook.duplicate";
    public static final String AI_GENERATE_SUCCESS = "ai.generate.success";
    public static final String AI_GENERATE_FAILED = "ai.generate.failed";
    public static final String AI_FALLBACK_USED = "ai.fallback.used";
    public static final String LINE_REPLY_SENT = "line.reply.sent";
    public static final String LINE_REPLY_FAILED = "line.reply.failed";
    public static final String LINE_PUSH_SENT = "line.push.sent";
    public static final String LINE_PUSH_FAILED = "line.push.failed";
    public static final String LINE_RETRY = "line.retry";
    public static final String MEMORY_CACHE_MISS = "memory.cache_miss";
    public static final String LOCK_TIMEOUT = "lock.timeout";
    public static final String EXECUTOR_REJECTED = "executor.rejected";
    public static final String PERSIST_FAILED = "persist.failed";
    public static final String INTERNAL_PUSH_INVOKED = "internal.push.invoked";
    public static final String USER_DATA_DELETED = "user.data.deleted";
    public static final String CHAT_RETENTION_CLEANED = "chat.retention.cleaned";
    public static final String WEBHOOK_PARSE_FAILED = "webhook.parse_failed";
    public static final String WEBHOOK_EVENT_FAILED = "webhook.event_failed";
    public static final String WEBHOOK_EVENT_SKIPPED = "webhook.event_skipped";
    public static final String LINE_REPLY_REQUESTED = "line.reply.requested";
    public static final String LINE_PUSH_REQUESTED = "line.push.requested";
    public static final String HTTP_REQUEST_COMPLETED = "http.request.completed";
    public static final String HTTP_REQUEST_FAILED = "http.request.failed";
    public static final String APP_LIFECYCLE = "app.lifecycle";

    private static final Set<String> REGISTERED = Set.of(USER_FOLLOW, USER_UNFOLLOW, USER_MESSAGE_RECEIVED,
            USER_POSTBACK, USER_RATE_LIMITED, WEBHOOK_RECEIVED, WEBHOOK_SIGNATURE_INVALID, WEBHOOK_DUPLICATE,
            AI_GENERATE_SUCCESS, AI_GENERATE_FAILED, AI_FALLBACK_USED, LINE_REPLY_SENT, LINE_REPLY_FAILED,
            LINE_PUSH_SENT, LINE_PUSH_FAILED, LINE_RETRY, MEMORY_CACHE_MISS, LOCK_TIMEOUT, EXECUTOR_REJECTED,
            PERSIST_FAILED, INTERNAL_PUSH_INVOKED, USER_DATA_DELETED, CHAT_RETENTION_CLEANED,
            WEBHOOK_PARSE_FAILED, WEBHOOK_EVENT_FAILED, WEBHOOK_EVENT_SKIPPED, LINE_REPLY_REQUESTED,
            LINE_PUSH_REQUESTED, HTTP_REQUEST_COMPLETED, HTTP_REQUEST_FAILED, APP_LIFECYCLE);

    /**
     * 校验固定事件登记，避免动态输入变成事件名称。
     * @param event 待检查事件名
     * @return 已登记且非空时返回 true
     */
    public static boolean isRegistered(String event) { return event != null && REGISTERED.contains(event); }

    private LogEvents() {}
}
