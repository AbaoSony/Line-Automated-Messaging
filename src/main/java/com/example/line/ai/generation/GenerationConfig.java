package com.example.line.ai.generation;

import com.example.line.ai.AiProperties;
import com.example.line.ai.TurnFlowProperties;
import com.example.line.ai.aggregation.AggregationProperties;
import com.example.line.ai.decision.DecisionProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import com.openai.core.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配唯一官方模型客户端，明确关闭 SDK 重试和 HTTP 报文日志。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ModelProperties.class,ApiCredentials.class,AiProperties.class,
        TurnFlowProperties.class,AggregationProperties.class,DecisionProperties.class})
class GenerationConfig {
    @Bean(destroyMethod = "close")
    OpenAIClient openAIClient(ApiCredentials credentials, ModelProperties properties) {
        return OpenAIOkHttpClient.builder().apiKey(credentials.apiKey()).maxRetries(0).logLevel(LogLevel.OFF)
                .timeout(Timeout.builder().connect(properties.connectTimeout()).read(properties.attemptTimeout())
                        .write(properties.attemptTimeout()).request(properties.attemptTimeout()).build()).build();
    }
}
