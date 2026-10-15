package com.example.line.ai.generation;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 保存已核实模型的能力与有限重试配置，拒绝把不支持的采样参数发送给推理模型。 */
@ConfigurationProperties("line.ai.model")
public record ModelProperties(@DefaultValue("gpt-6.1-sol") String name,
                              @DefaultValue("low") String reasoning,
                              Double temperature,
                              @DefaultValue("8192") int maxInputTokens,
                              @DefaultValue("1024") int maxOutputTokens,
                              @DefaultValue("8s") Duration totalTimeout,
                              @DefaultValue("4s") Duration attemptTimeout,
                              @DefaultValue("1s") Duration connectTimeout,
                              @DefaultValue("100ms") Duration retryBackoff) {
    /**
     * 校验负责人选择的两种模型及官方支持参数，未知能力须明确接入后才开放。
     * @throws IllegalArgumentException 模型、推理、采样参数或预算无效时抛出
     */
    public ModelProperties {
        if (!Set.of("gpt-6.1-sol","gpt-6-astra").contains(name)
                || !Set.of("low","medium","high","xhigh","max").contains(reasoning) || temperature != null
                || maxInputTokens < 1 || maxOutputTokens < 1) throw new IllegalArgumentException("Unsupported AI model parameters");
        for (Duration duration : new Duration[]{totalTimeout,attemptTimeout,connectTimeout,retryBackoff})
            if (duration == null || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Invalid model timeout");
    }
}
