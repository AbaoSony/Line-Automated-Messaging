package com.example.line.controller;

import com.example.line.service.SignatureVerifier;
import com.example.line.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {
    private final SignatureVerifier verifier;
    private final WebhookService webhookService;

    public WebhookController(SignatureVerifier verifier, WebhookService webhookService) {
        this.verifier = verifier;
        this.webhookService = webhookService;
    }

    @PostMapping("/line/webhook")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
            @RequestHeader(value = "X-Line-Signature", required = false) String signature) {
        if (!verifier.isValid(body, signature)) {
            return ResponseEntity.badRequest().build();
        }
        webhookService.submit(body);
        return ResponseEntity.ok().build();
    }
}
