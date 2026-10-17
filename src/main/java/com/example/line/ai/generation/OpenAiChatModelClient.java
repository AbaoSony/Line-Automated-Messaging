package com.example.line.ai.generation;

import com.example.line.common.execution.Deadline;
import com.example.line.common.logging.BusinessLog;
import com.example.line.common.logging.LogEvents;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import com.example.line.ai.generation.transport.ModelCallScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

/** 将 Responses SDK 限制在适配器内；应用只对 5xx/网络/超时重试一次，迟到响应没有发送副作用。 */
@Component
public final class OpenAiChatModelClient implements ChatModelClient {
    private static final int MAX_ATTEMPTS = 2;
    private final OpenAIClient client;
    private final ModelProperties properties;
    private final MeterRegistry meters;
    /**
     * 注入已关闭自动重试的客户端与模型预算。
     * @param client 官方 SDK 客户端
     * @param properties 模型配置
     * @param meters 按尝试统计耗时和失败，不使用用户标签
     */
    public OpenAiChatModelClient(OpenAIClient client, ModelProperties properties, MeterRegistry meters) {
        this.client = client; this.properties = properties; this.meters = meters;
    }
    /**
     * 在原请求截止时间和模型阶段上限内生成，所有 SDK 对象仅保留在本方法内。
     * @param request 不含凭证的完整上下文
     * @return 完整原始文本及响应状态，空白/未完成由编排分类
     * @throws ModelFailure 预算耗尽、HTTP、网络、中断或无效响应时抛出
     */
    @Override public ChatResult generate(ChatRequest request) {
        long begin = request.deadline().clock().getAsLong();
        var deadline = new Deadline(request.deadline().startedNanos(), Math.min(request.deadline().expiresNanos(),
                begin + properties.totalTimeout().toNanos()), request.deadline().clock());
        var input = new ArrayList<ResponseInputItem>();
        for (var message : request.history()) input.add(item(message.role(), message.content()));
        for (String message : request.currentMessages()) input.add(item("user", message));
        var params = ResponseCreateParams.builder().model(properties.name()).instructions(request.systemPrompt())
                .inputOfResponse(input).maxOutputTokens(properties.maxOutputTokens()).store(false)
                .reasoning(Reasoning.builder().effort(ReasoningEffort.of(properties.reasoning())).build()).build();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new ModelFailure("interrupted");
            if (!deadline.permits(Duration.ofMillis(1))) throw new ModelFailure("deadline");
            Duration timeout = deadline.limit(properties.attemptTimeout());
            long started = System.nanoTime();
            CompletableFuture<Response> pending = null;
            try (var physical=ModelCallScope.open()) {
                var options = RequestOptions.builder().timeout(Timeout.builder()
                        .connect(min(timeout,properties.connectTimeout())).read(timeout).write(timeout).request(timeout).build()).build();
                pending = client.async().responses().create(params.toBuilder().putAdditionalHeader(ModelCallScope.HEADER,physical.id()).build(), options);
                physical.track(pending);
                Response response = pending.get(deadline.limit(timeout).toNanos(), TimeUnit.NANOSECONDS);
                if (!deadline.permits(Duration.ZERO)) throw new ModelFailure("deadline");
                var text = new StringBuilder();
                boolean completed = response.status().map(ResponseStatus.COMPLETED::equals).orElse(false);
                for (var output : response.output()) if (output.message().isPresent()) {
                    var message = output.message().get();
                    completed &= message.status().equals(ResponseOutputMessage.Status.COMPLETED);
                    for (var content : message.content()) content.outputText().ifPresent(value -> text.append(value.text()));
                }
                var usage = response.usage();
                String model = response.model().string().orElse(properties.name());
                String incompleteReason=response.incompleteDetails().flatMap(Response.IncompleteDetails::reason)
                        .map(reason -> java.util.Set.of("max_output_tokens","max_messages","content_filter","steered").contains(reason.asString())
                                ? reason.asString():"unknown").orElse(null);
                Integer reasoningTokens=usage.flatMap(value -> value._outputTokensDetails().asKnown())
                        .flatMap(value -> value._reasoningTokens().asKnown()).map(Math::toIntExact).orElse(null);
                return new ChatResult(text.toString(), model, usage.map(value -> Math.toIntExact(value.inputTokens())).orElse(null),
                        usage.map(value -> Math.toIntExact(value.outputTokens())).orElse(null),
                        Duration.ofNanos(request.deadline().clock().getAsLong()-begin).toMillis(), completed ? "COMPLETED" : "INCOMPLETE",
                        incompleteReason,reasoningTokens);
            } catch (InterruptedException failure) {
                if (pending != null) pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new ModelFailure("interrupted");
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                if (pending != null) pending.cancel(true);
                Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
                Integer status = cause instanceof OpenAIServiceException api ? api.statusCode() : null;
                String kind = status != null ? "http_" + status : cause instanceof TimeoutException ? "timeout"
                        : cause instanceof OpenAIIoException ? "network" : cause instanceof ModelFailure model ? model.kind() : "invalid_response";
                boolean retryable = status != null ? status >= 500 && status <= 599
                        : cause instanceof OpenAIIoException || cause instanceof TimeoutException;
                meters.counter("ai.attempt.failed", "reason", status != null ? "http" : kind).increment();
                boolean retry = retryable && attempt < MAX_ATTEMPTS && deadline.permits(properties.retryBackoff().plusMillis(1))
                        && request.reserveRetry().getAsBoolean();
                BusinessLog.write(LoggerFactory.getLogger(getClass()), Level.WARN, LogEvents.AI_GENERATE_RETRY,
                        "Model attempt failed", BusinessLog.fields("attempt", attempt, "retry", retry, "error_type", kind,
                                "http_status", status, "outcome", "FAILED", "duration_ms", (System.nanoTime()-started)/1_000_000));
                if (!retry) throw new ModelFailure(kind);
                try { TimeUnit.NANOSECONDS.sleep(properties.retryBackoff().toNanos()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ModelFailure("interrupted"); }
            } finally { meters.timer("ai.attempt.duration").record(System.nanoTime()-started, TimeUnit.NANOSECONDS); }
        }
        throw new ModelFailure("retry_exhausted");
    }
    private static ResponseInputItem item(String role, String content) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(EasyInputMessage.Role.of(role)).content(content).build());
    }
    private static Duration min(Duration first, Duration second) { return first.compareTo(second) < 0 ? first : second; }
}
