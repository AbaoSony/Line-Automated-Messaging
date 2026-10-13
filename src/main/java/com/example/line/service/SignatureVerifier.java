package com.example.line.service;

import com.example.line.config.LineProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class SignatureVerifier {
    private final byte[] secret;

    public SignatureVerifier(LineProperties properties) {
        secret = properties.getChannelSecret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        final byte[] supplied;
        try {
            supplied = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(rawBody), supplied);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
