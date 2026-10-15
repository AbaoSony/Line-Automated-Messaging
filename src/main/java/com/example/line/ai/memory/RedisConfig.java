package com.example.line.ai.memory;

import com.example.line.ai.AiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 装配有界命令超时的 Redis 客户端，只连接部署方提供的服务，不搭建生产数据库。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RedisProperties.class,MemoryProperties.class})
class RedisConfig {
    @Bean
    LettuceConnectionFactory redisConnectionFactory(RedisProperties properties, AiProperties ai) {
        var standalone = new RedisStandaloneConfiguration(properties.host(),properties.port());
        if (properties.username() != null && !properties.username().isBlank()) standalone.setUsername(properties.username());
        if (properties.password() != null && !properties.password().isBlank()) standalone.setPassword(properties.password());
        var client = LettuceClientConfiguration.builder().commandTimeout(ai.dependencyTimeout())
                .shutdownTimeout(ai.dependencyTimeout());
        if (properties.ssl()) client.useSsl();
        return new LettuceConnectionFactory(standalone,client.build());
    }
    @Bean StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }
}
