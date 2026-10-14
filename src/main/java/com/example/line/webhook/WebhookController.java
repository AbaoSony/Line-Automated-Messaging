package com.example.line.webhook;

import com.example.line.webhook.signature.SignatureVerifier;
import com.example.line.webhook.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * LINE Webhook 的 HTTP 入口，只负责原始字节验签与快速确认。
 * 与 SignatureVerifier、WebhookService 协作，把解析、去重、Handler 和发送留在工作线程。
 */
@RestController
public class WebhookController {
    private final SignatureVerifier verifier;
    private final WebhookService webhookService;

    /**
     * 注入验签服务和异步接入服务，保持 Controller 不依赖具体事件类型。
     *
     * @param verifier 使用 Channel Secret 验证原始请求字节的服务
     * @param webhookService 提交有界线程池并分发事件的服务
     */
    public WebhookController(SignatureVerifier verifier, WebhookService webhookService) {
        this.verifier = verifier;
        this.webhookService = webhookService;
    }

    /**
     * 先验签后提交异步处理，不等待业务和 Reply 完成，尽快向 LINE 返回 200。
     *
     * @param body 框架读取的原始请求体字节，不可先格式化或重新序列化再验签
     * @param signature X-Line-Signature 的 Base64 值；缺失、格式非法或不匹配均视为无效
     * @return 无效签名返回空体 400；有效签名提交后返回空体 200，不表示业务成功
     * @throws IllegalStateException 运行环境无法提供 HMAC-SHA256 时由验签服务抛出
     */
    @PostMapping("/line/webhook")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
            @RequestHeader(value = "X-Line-Signature", required = false) String signature) {
        if (!verifier.isValid(body, signature)) {
            return ResponseEntity.badRequest().build();
        }
        // 入队拒绝仍沿用快速 200 的既定策略，由接入服务记录；这不是可靠投递保证。
        webhookService.submit(body);
        return ResponseEntity.ok().build();
    }
}
