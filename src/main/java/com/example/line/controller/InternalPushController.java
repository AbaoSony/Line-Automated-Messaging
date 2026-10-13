package com.example.line.controller;

import com.example.line.config.InternalPushProperties;
import com.example.line.service.LineMessageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalPushController {
    private final InternalPushProperties properties;
    private final LineMessageService messages;
    public InternalPushController(InternalPushProperties properties, LineMessageService messages) {
        this.properties = properties;
        this.messages = messages;
    }
    public record PushTextRequest(String userId, String text) {}
    public record PushResponse(String status, String retryKey, boolean alreadyAccepted) {}
    @PostMapping("/internal/push")
    public ResponseEntity<?> push(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                 @RequestBody PushTextRequest request) {
        if (!properties.isEnabled()) return ResponseEntity.notFound().build();
        if (token == null || !MessageDigest.isEqual(properties.getToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        var receipt = messages.pushText(request.userId(), request.text());
        return ResponseEntity.ok(new PushResponse("ACCEPTED", receipt.retryKey().toString(), receipt.alreadyAccepted()));
    }
}
