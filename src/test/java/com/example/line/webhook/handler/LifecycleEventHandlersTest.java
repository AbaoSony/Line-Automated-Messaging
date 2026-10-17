package com.example.line.webhook.handler;

import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** These handlers intentionally have no SDK dependency: verify their real side effect, the log. */
/** 验证关注、取消关注和 Postback Handler 的日志副作用与敏感字段不输出。 */
@ExtendWith(OutputCaptureExtension.class)
class LifecycleEventHandlersTest {
    // 场景：关注事件记录用户和事件标识，不输出 Reply Token。
    @Test
    void followRecordsUserIdWithoutLeakingReplyToken(CapturedOutput output) throws Exception {
        var event = (FollowEvent) event("follow");
        var users=org.mockito.Mockito.mock(com.example.line.user.UserService.class);
        org.mockito.Mockito.when(users.recordStatus(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.any(com.example.line.user.LifecycleOrder.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedStage(true));
        var handler = new FollowEventHandler(users);
        assertThat(handler.eventType()).isEqualTo(FollowEvent.class);
        handler.handle(event);
        assertThat(output).contains("LINE follow userId=" + event.source().userId(), event.webhookEventId())
                .doesNotContain(event.replyToken());
    }

    // 场景：取消关注事件记录对应用户和事件标识。
    @Test
    void unfollowRecordsUserId(CapturedOutput output) throws Exception {
        var event = (UnfollowEvent) event("unfollow");
        var users=org.mockito.Mockito.mock(com.example.line.user.UserService.class);
        org.mockito.Mockito.when(users.recordStatus(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.any(com.example.line.user.LifecycleOrder.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedStage(true));
        var handler = new UnfollowEventHandler(users);
        assertThat(handler.eventType()).isEqualTo(UnfollowEvent.class);
        handler.handle(event);
        assertThat(output).contains("LINE unfollow userId=" + event.source().userId(), event.webhookEventId());
    }

    // 场景：Postback 完整记录业务动作数据，凭证仍禁止进入日志。
    @Test
    void postbackKeepsFullBusinessDataAndDoesNotLogToken(CapturedOutput output) throws Exception {
        var event = (PostbackEvent) event("postback");
        var handler = new PostbackEventHandler();
        assertThat(handler.eventType()).isEqualTo(PostbackEvent.class);
        handler.handle(event);
        assertThat(output).contains("LINE postback received eventId=" + event.webhookEventId(), event.postback().data())
                .doesNotContain(event.replyToken());
    }

    private Event event(String sample) throws IOException {
        return ModelObjectMapper.createNewObjectMapper().readValue(
                Files.readAllBytes(Path.of("docs/examples/webhook/webhook-" + sample + ".json")), CallbackRequest.class).events().get(0);
    }
}
