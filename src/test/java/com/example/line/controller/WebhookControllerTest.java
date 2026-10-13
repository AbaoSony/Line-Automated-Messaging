package com.example.line.controller;

import com.example.line.config.LineProperties;
import com.example.line.service.SignatureVerifier;
import com.example.line.service.WebhookService;
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

    @Test
    void correctSignatureReturns200AndSubmitsOriginalBytes() throws Exception {
        mvc.perform(post("/line/webhook").contentType("application/json").content(body)
                .header("X-Line-Signature", sign(body))).andExpect(status().isOk());
        verify(service).submit(org.mockito.AdditionalMatchers.aryEq(body));
    }

    @Test
    void wrongSignatureReturns400WithoutProcessing() throws Exception {
        mvc.perform(post("/line/webhook").contentType("application/json").content(body)
                .header("X-Line-Signature", sign("tampered".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest());
        verify(service, never()).submit(any());
    }

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
