package com.example.line.messaging.internal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定内部 Push 调试开关与独立鉴权配置，由 {@link LineClientConfig} 注册，
 * 供 {@link com.example.line.messaging.internal.InternalPushController} 决定是否接受调试请求。
 * 开关来自 LINE_INTERNAL_PUSH_ENABLED，默认 false；鉴权值来自 LINE_INTERNAL_PUSH_TOKEN，
 * 仅启用开关时必填。配置对象不生成包含鉴权值的字符串表示，避免意外进入日志。
 */
@ConfigurationProperties("line.internal-push")
public final class InternalPushProperties {
    private final boolean enabled;
    private final String token;
    /**
     * 在配置绑定时检查启用调试接口所需的鉴权条件。
     *
     * @param enabled 是否启用内部 Push，未配置环境变量时为 false
     * @param token 独立调试鉴权值；enabled 为 true 时必须非空白，不应复用 LINE 凭证
     * @throws IllegalArgumentException 启用了调试接口但未配置非空白鉴权值时抛出
     */
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
