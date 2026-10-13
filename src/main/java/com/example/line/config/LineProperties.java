package com.example.line.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "line.bot")
public final class LineProperties {
    private final String channelSecret;
    private final String channelToken;

    public LineProperties(String channelSecret, String channelToken) {
        List<String> missing = new ArrayList<>();
        if (channelSecret == null || channelSecret.isBlank()) {
            missing.add("line.bot.channel-secret (LINE_CHANNEL_SECRET)");
        }
        if (channelToken == null || channelToken.isBlank()) {
            missing.add("line.bot.channel-token (LINE_CHANNEL_TOKEN)");
        }
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
