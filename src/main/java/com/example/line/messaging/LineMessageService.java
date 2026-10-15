package com.example.line.messaging;

import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogContext;
import com.example.line.common.logging.LogData;
import com.example.line.common.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 封装 LINE Reply/Push 发送，是 Handler、内部调试接口与官方 SDK 之间的业务边界。
 * 与 LineMessages 构造合法常用消息，与 LineApiCaller 统一等待和错误分类，
 * 并在调用 SDK 前限制单次消息数量。
 */
@Service
public class LineMessageService {
    private static final Logger log = LoggerFactory.getLogger(LineMessageService.class);
    private final MessagingApiClient client;
    private final LineApiCaller caller;
    private final com.example.line.common.execution.UserWorkTracker tracker;

    /**
     * 注入消息客户端与统一发送策略，调用方无需重复实现重试或错误映射。
     *
     * @param client LineClientConfig 提供的消息客户端，使用配置的 Access Token 鉴权
     * @param caller 执行有限等待、Push 退避与安全异常转换的调用器
     */
    public LineMessageService(MessagingApiClient client, LineApiCaller caller) {
        this(client,caller,new com.example.line.common.execution.UserWorkTracker());
    }

    /**
     * 接入统一发送工作跟踪，让手动 Push 与 Reply 同样受用户删除屏障保护。
     * @param client 官方消息客户端
     * @param caller 有界发送策略
     * @param tracker 单实例用户工作跟踪
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LineMessageService(MessagingApiClient client,LineApiCaller caller,com.example.line.common.execution.UserWorkTracker tracker) {
        this.client = client;
        this.caller = caller;
        this.tracker=tracker;
    }

    /**
     * Reply is free and does not consume the monthly message quota; prefer it to Push.
     * replyToken is single-use and short-lived. Use immediately in the event worker,
     * never persist for delayed work. Reply has no retry-key support and is not retried.
     * 即时回复一条文本；Reply 免费、不计入月度消息额度，业务应优先使用。
     * 当前事件的短效一次性 Reply Token 不得用于持久化或延迟任务。
     *
     * @param replyToken 当前事件的非空白 Reply Token，与 Channel Access Token 不同
     * @param text 非空白文本，最多 5000 个 UTF-16 单元
     * @throws IllegalArgumentException Token 或文本不满足约束时抛出
     * @throws LineSendException SDK 回复失败、等待超时或被中断时抛出；不自动重试
     */
    public void replyText(String replyToken, String text) {
        replyMessages(replyToken, List.of(LineMessages.text(text)));
    }

    /**
     * 在 AI 原始截止时间内发送一次文本，不重试、不改 Push，超时结果视为未知。
     * @param replyToken 当前短效凭证
     * @param text 经 LINE 长度校验的文本
     * @param deadline 原请求截止时间
     * @throws IllegalArgumentException 文本或凭证无效时抛出
     * @throws LineSendException LINE 失败或超时时抛出，调用方不得补发兜底
     */
    public void replyText(String replyToken,String text,com.example.line.common.execution.Deadline deadline) {
        if(replyToken==null || replyToken.isBlank())throw new IllegalArgumentException("Missing reply token");
        var request=new ReplyMessageRequest.Builder(replyToken,List.of(LineMessages.text(text))).build();
        try(var lease=sending(org.slf4j.MDC.get("user_id"),false)) {
            BusinessLog.write(log,Level.INFO,LogEvents.LINE_REPLY_REQUESTED,"LINE reply requested",BusinessLog.fields("messages",LogData.business(request.messages())));
            caller.execute("reply",null,() -> client.replyMessage(request),deadline);
            BusinessLog.write(log,Level.INFO,LogEvents.LINE_REPLY_SENT,"LINE reply accepted",BusinessLog.fields("outcome","SUCCESS"));
        }
    }

    /**
     * 使用当前事件 Token 一次回复多条消息，不拆批、不自动改为 Push。
     *
     * @param replyToken 非空白、尚可使用的短效一次性 Reply Token，有效性最终由 LINE 判定
     * @param messages 1..5 条非 null 的 SDK 消息；通用封装不逐项校验原始 SDK 消息的全部字段
     * @throws IllegalArgumentException Token 为空白、列表缺失、数量越界或存在 null 元素时抛出
     * @throws LineSendException SDK 回复失败、等待超时或被中断时抛出
     */
    public void replyMessages(String replyToken, List<Message> messages) {
        if (replyToken == null || replyToken.isBlank()) {
            throw new IllegalArgumentException("Missing reply token");
        }
        var request = new ReplyMessageRequest.Builder(replyToken, validateMessages(messages)).build();
        try(var lease=sending(org.slf4j.MDC.get("user_id"),false)) {
            BusinessLog.write(log, Level.INFO, LogEvents.LINE_REPLY_REQUESTED, "LINE reply requested",
                    BusinessLog.fields("messages", LogData.business(request.messages())));
            caller.execute("reply", null, () -> client.replyMessage(request));
            BusinessLog.write(log, Level.INFO, LogEvents.LINE_REPLY_SENT, "LINE reply accepted",
                    BusinessLog.fields("outcome", "SUCCESS"));
        }
    }

    /**
     * Push consumes quota. All automatic retries reuse the original key and payload.
     * 主动推送一条文本，会消耗消息额度；能用即时 Reply 的业务优先用 Reply。
     *
     * @param userId U 加 32 个小写十六进制字符的用户 ID，属于当前 Channel 可发送目标
     * @param text 非空白文本，最多 5000 个 UTF-16 单元
     * @return 本次 Push 的 Retry Key 与已接受状态，不保证用户实际收件
     * @throws IllegalArgumentException userId 或文本不满足本地约束时抛出
     * @throws LineSendException LINE 拒绝、有限重试耗尽或调用被中断时抛出
     */
    public PushReceipt pushText(String userId, String text) {
        return pushMessages(userId, List.of(LineMessages.text(text)));
    }

    /**
     * 主动推送 1..5 条消息，自动重试复用相同 Key 和请求体以避免重复执行。
     *
     * @param userId U 加 32 个小写十六进制字符的用户 ID；当前封装不接受 group/room ID
     * @param messages 1..5 条非 null 的 SDK 消息，不自动拆成多个请求
     * @return 本次接受回执；同一方法重新调用会生成新 Key，不能替代业务幂等
     * @throws IllegalArgumentException 用户 ID、列表或消息数量/元素不满足约束时抛出
     * @throws LineSendException 上游 4xx、有限瞬时错误重试耗尽或中断时抛出
     */
    public PushReceipt pushMessages(String userId, List<Message> messages) {
        if (userId == null || !userId.matches("U[0-9a-f]{32}")) {
            throw new IllegalArgumentException("userId must be U followed by 32 lowercase hexadecimal characters");
        }
        var request = new PushMessageRequest.Builder(userId, validateMessages(messages)).build();
        // 必须从首次请求就设置 Key，不能等遇到超时后再生成，否则无法去除已执行的重复请求。
        UUID retryKey = UUID.randomUUID();
        try (var scope = LogContext.user(userId);var lease=sending(userId,true)) {
            BusinessLog.write(log, Level.INFO, LogEvents.LINE_PUSH_REQUESTED, "LINE push requested",
                    BusinessLog.fields("messages", LogData.business(request.messages())));
            boolean alreadyAccepted = caller.execute("push", retryKey, () -> client.pushMessage(retryKey, request));
            BusinessLog.write(log, Level.INFO, LogEvents.LINE_PUSH_SENT, "LINE push accepted",
                    BusinessLog.fields("outcome", "SUCCESS"));
            return new PushReceipt(retryKey, alreadyAccepted);
        }
    }

    private com.example.line.common.execution.UserWorkTracker.Lease sending(String user,boolean push) {
        var lease=tracker.register(user);
        if(lease==null) {
            BusinessLog.write(log,Level.WARN,push?LogEvents.LINE_PUSH_FAILED:LogEvents.LINE_REPLY_FAILED,
                    "LINE send blocked by user work boundary",BusinessLog.fields("outcome","FAILED","reason","user_work_blocked"));
            throw new LineSendException(LineSendException.Reason.TRANSIENT_FAILURE,null);
        }
        return lease;
    }

    private List<Message> validateMessages(List<Message> messages) {
        // LINE 单次最多 5 个根消息对象；Quick Reply 按钮在消息内部，不另占根消息数量。
        if (messages == null || messages.isEmpty() || messages.size() > 5
                || messages.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("A LINE request must contain 1..5 non-null messages");
        }
        // 固定消息列表，避免调用方在 Push 自动重试间改变收件内容。
        return List.copyOf(messages);
    }

    /**
     * 供内部接口输出接受状态的摘要，不保存消息正文或 SDK 原始回执。
     *
     * @param retryKey 本次调用使用的 UUID；当前仅在调用内有效，不持久化
     * @param alreadyAccepted 是否由带 accepted-request-id 的 409 确认此前已接受
     */
    public record PushReceipt(UUID retryKey, boolean alreadyAccepted) {}
}
