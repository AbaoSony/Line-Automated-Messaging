package com.example.line.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定启动时必需的 LINE Channel 配置，位于客户端装配和 Webhook 验签之前。
 * Secret 来自 LINE_CHANNEL_SECRET，Access Token 来自 LINE_CHANNEL_TOKEN，
 * application.yml 通过环境变量占位符映射，也允许外部 Spring 配置覆盖。
 * 与 LineClientConfig、SignatureVerifier 协作；不生成会暴露凭证的 toString。
 */
@ConfigurationProperties(prefix = "line.bot")
public final class LineProperties {
    private final String channelSecret;
    private final String channelToken;

    /**
     * 在绑定阶段快速拒绝缺失配置，避免服务启动后才发现无法验签或发送消息。
     *
     * @param channelSecret 必填、非空白的 Channel Secret，仅用于原始请求体签名校验
     * @param channelToken 必填、非空白的 Channel Access Token，用于 SDK 出站鉴权
     * @throws IllegalArgumentException 任一凭证缺失或为空白时抛出，错误只包含配置名
     */
    public LineProperties(String channelSecret, String channelToken) {
        List<String> missing = new ArrayList<>();
        if (channelSecret == null || channelSecret.isBlank()) {
            missing.add("line.bot.channel-secret (LINE_CHANNEL_SECRET)");
        }
        if (channelToken == null || channelToken.isBlank()) {
            missing.add("line.bot.channel-token (LINE_CHANNEL_TOKEN)");
        }
        // 仅报告缺失项的名称，不能把另一项已配置凭证带入启动失败日志。
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required LINE configuration: "
                    + String.join(", ", missing) + ". Set non-blank environment variables or configuration properties.");
        }
        this.channelSecret = channelSecret;
        this.channelToken = channelToken;
    }

    public String getChannelSecret() {
        return channelSecret;
    }

    public String getChannelToken() {
        return channelToken;
    }
}
