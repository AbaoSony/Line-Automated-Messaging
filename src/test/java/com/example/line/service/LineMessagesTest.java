package com.example.line.service;

import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LineMessagesTest {
    @Test
    void allFactoriesSerializeToDocumentedFiveMessagePayload() throws Exception {
        List<Message> messages = List.of(
                LineMessages.text("hello"),
                LineMessages.sticker("446", "1988"),
                LineMessages.image(URI.create("https://example.com/order.png"), URI.create("https://example.com/order-preview.png")),
                LineMessages.flexCard("订单通知", "订单 A202610130001 已发货"),
                LineMessages.quickReply("请选择操作", List.of(new LineMessages.QuickOption("查询订单", "查询订单"))));
        var mapper = ModelObjectMapper.createNewObjectMapper();
        var expected = mapper.readTree(Files.readAllBytes(Path.of("docs/examples/push-five-messages-request.json")));
        var actual = mapper.valueToTree(new PushMessageRequest.Builder("U" + "a".repeat(32), messages).build());
        assertThat(actual).isEqualTo(expected);
    }
    @Test
    void invalidTextImagesAndQuickOptionsFailLocally() {
        assertThatThrownBy(() -> LineMessages.text("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineMessages.text("x".repeat(5001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineMessages.image(URI.create("http://example.com/image"), URI.create("https://example.com/p")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineMessages.quickReply("prompt",
                java.util.Collections.nCopies(14, new LineMessages.QuickOption("label", "text"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
