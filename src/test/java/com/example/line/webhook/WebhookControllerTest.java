package com.example.line.webhook;

import com.example.line.client.LineProperties;
import com.example.line.webhook.signature.SignatureVerifier;
import com.example.line.webhook.WebhookService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 Webhook 正确签名传递原始字节、错误签名返回 400 且不提交事件。 */
class WebhookControllerTest {
    private final WebhookService service = mock(WebhookService.class);
    private MockMvc mvc;
    private final byte[] body = """
            { "destination": "Utest", "events": [], "extra": "你好\\n世界" }
            """.getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(
                new SignatureVerifier(new LineProperties("test-secret", "test-token")), service)).build();
    }

    // 场景：正确签名返回 200，异步服务收到未重写的原始字节。
    @Test
    void correctSignatureReturns200AndSubmitsOriginalBytes() throws Exception {
        mvc.perform(post("/line/webhook").contentType("application/json").content(body)
                .header("X-Line-Signature", sign(body))).andExpect(status().isOk());
        verify(service).submit(org.mockito.AdditionalMatchers.aryEq(body));
    }

    // 场景：签名属于其他内容时返回 400，不进行业务提交。
    @Test
    void wrongSignatureReturns400WithoutProcessing() throws Exception {
        mvc.perform(post("/line/webhook").contentType("application/json").content(body)
                .header("X-Line-Signature", sign("tampered".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest());
        verify(service, never()).submit(any());
    }

    // 场景：缺失签名或 Base64 格式非法时均拒绝请求。
    @Test
    void missingOrMalformedSignatureReturns400() throws Exception {
        mvc.perform(post("/line/webhook").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/line/webhook").content(body).header("X-Line-Signature", "not-base64!"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String sign(byte[] input) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(input));
    }
}
