package com.example.line.config;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("line.internal-push")
public final class InternalPushProperties {
    private final boolean enabled;
    private final String token;
    public InternalPushProperties(boolean enabled, String token) {
        if (enabled && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("Set LINE_INTERNAL_PUSH_TOKEN when LINE_INTERNAL_PUSH_ENABLED=true");
        }
        this.enabled = enabled;
        this.token = token;
    }
    public boolean isEnabled() { return enabled; }
    public String getToken() { return token; }
}
