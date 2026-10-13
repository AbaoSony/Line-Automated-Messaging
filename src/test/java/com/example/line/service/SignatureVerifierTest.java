package com.example.line.service;

import com.example.line.config.LineProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {
    @Test
    void matchesIndependentRfc4231HmacSha256Vector() {
        // RFC 4231 case 2 is a public test vector, not a channel credential.
        var verifier = verifier("Jefe");
        String signature = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"));
        assertThat(verifier.isValid(bytes("what do ya want for nothing?"), signature)).isTrue();
    }

    @Test
    void unicodeBodyMustMatchByteForByteIncludingWhitespaceAndNewlines() throws Exception {
        var verifier = verifier("test-secret");
        byte[] body = bytes("{ \"events\": [], \"text\": \"你好\\n世界\" }\n");
        String signature = sign(body, "test-secret");
        assertThat(verifier.isValid(body, signature)).isTrue();
        assertThat(verifier.isValid(bytes("{\"events\":[],\"text\":\"你好\\n世界\"}"), signature)).isFalse();
        assertThat(verifier.isValid(bytes(new String(body, StandardCharsets.UTF_8) + "\n"), signature)).isFalse();
        byte[] tampered = body.clone();
        tampered[0] = '[';
        assertThat(verifier.isValid(tampered, signature)).isFalse();
        assertThat(verifier("different-test-secret").isValid(body, signature)).isFalse();
    }

    @Test
    void missingMalformedOrWrongLengthSignaturesAreRejected() {
        for (String signature : new String[] {null, "", " ", "not-base64!", "AA==", "AAAA"}) {
            assertThat(verifier("test-secret").isValid(bytes("{}"), signature)).isFalse();
        }
    }

    @Test
    void concurrentVerificationDoesNotShareMutableMacState() throws Exception {
        var verifier = verifier("test-secret");
        var executor = Executors.newFixedThreadPool(8);
        var results = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 100; i++) {
                byte[] body = bytes("request-" + i);
                String signature = sign(body, "test-secret");
                results.add(executor.submit(() -> verifier.isValid(body, signature)));
            }
            for (var result : results) assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SignatureVerifier verifier(String secret) {
        return new SignatureVerifier(new LineProperties(secret, "test-token"));
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String sign(byte[] body, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bytes(secret), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
