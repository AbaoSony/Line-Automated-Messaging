package com.example.line.ai.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 读取外部 API Key，不生成会暴露配置值的 record/toString。 */
@ConfigurationProperties("line.ai.credentials")
final class ApiCredentials {
    private final String apiKey;
    ApiCredentials(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Missing OPENAI_API_KEY");
        this.apiKey = apiKey;
    }
    String apiKey() { return apiKey; }
}
