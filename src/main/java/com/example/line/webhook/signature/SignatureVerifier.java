package com.example.line.webhook.signature;

import com.example.line.client.LineProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * 为 WebhookController 验证 LINE 签名，使用 LineProperties 的 Channel Secret，
 * 在 JSON 解析之前对原始请求字节执行 HMAC-SHA256 与 Base64 摘要比较。
 * 无效签名仅返回 false，不记录请求体、签名或密钥。
 */
@Service
public class SignatureVerifier {
    private final byte[] secret;

    /**
     * 保存验签所需的 UTF-8 密钥字节，不持有可输出凭证的日志上下文。
     *
     * @param properties 已校验的必填配置，Secret 来源为 LINE_CHANNEL_SECRET 或外部 Spring 配置
     */
    public SignatureVerifier(LineProperties properties) {
        secret = properties.getChannelSecret().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 验证请求体是否匹配 LINE 提供的 Base64 签名。
     *
     * @param rawBody 非 null 的原始请求体字节；空格、换行、转义和 UTF-8 编码均不可预先修改
     * @param signature X-Line-Signature 原始值；null、空白、非法 Base64 均视为无效
     * @return 签名解码后的摘要与原始字节的 HMAC-SHA256 相同为 true，否则为 false
     * @throws IllegalStateException JDK 环境无法创建或初始化 HMAC-SHA256 时抛出
     */
    public boolean isValid(byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        final byte[] supplied;
        try {
            // LINE 传入 Base64 文本，先解码为摘要字节再比较；不能拿 JSON 重新序列化来计算。
            supplied = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            // Mac 有可变内部状态，每次调用独立创建，避免并发 Webhook 共享状态导致验签错误。
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            // 避免按摘要匹配前缀提前返回；摘要相等才允许接入层提交异步业务。
            return MessageDigest.isEqual(mac.doFinal(rawBody), supplied);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
