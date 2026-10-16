package com.example.line.ai;

import com.example.line.ai.aggregation.InboundMember;
import com.example.line.ai.decision.DecisionProperties;
import com.example.line.ai.memory.ConversationMemory;
import com.example.line.chat.ChatMessageService;
import com.example.line.common.execution.*;
import com.example.line.messaging.LineMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.*;

/** 提示容量不能驱逐刚发送或结果未知的用户，造成冷却失效后追加回复。 */
class TurnReplyServiceTest {
    @Test void fullCooldownTableSuppressesNewHintsWithoutEvictingExistingConsumedOpportunity() {
        var env=new MockEnvironment().withProperty("line.ai.technical-replies-enabled","true").withProperty("line.ai.max-users","1");
        var ai=Binder.get(env).bindOrCreate("line.ai",AiProperties.class);
        var decision=Binder.get(env).bindOrCreate("line.ai.decision",DecisionProperties.class);
        var line=mock(LineMessageService.class);
        var service=new TurnReplyService(ai,decision,line,mock(ChatMessageService.class),mock(ConversationMemory.class),
                new UserWorkTracker(),new ObjectMapper(),new SimpleMeterRegistry(),mock(com.example.line.chat.ChatTurnService.class));
        service.rejected(member("Ufictional-first",1),"fictional-first-hint",Deadline.current(Duration.ofSeconds(45)),"BUSY");
        service.rejected(member("Ufictional-second",2),"fictional-capacity-hint",Deadline.current(Duration.ofSeconds(45)),"RATE_LIMITED");
        service.rejected(member("Ufictional-first",3),"fictional-repeat-hint",Deadline.current(Duration.ofSeconds(45)),"FALLBACK");
        verify(line).replyText(eq("fictional-first-hint"),anyString(),any());verifyNoMoreInteractions(line);
    }
    private InboundMember member(String user,long sequence) {
        return new InboundMember(user,"fictional-event-"+sequence,"fictional-message-"+sequence,"完整虛構原文",Instant.EPOCH,Instant.EPOCH,sequence,"fictional-trace");
    }
}
