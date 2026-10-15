package com.example.line.common.logging;

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

    private LogEvents() {}
}
