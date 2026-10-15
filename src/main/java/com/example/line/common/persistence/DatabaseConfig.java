package com.example.line.common.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 装配业务连接池与定时调度；Flyway 复用业务数据源，只通过迁移脚本维护结构。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(DatabaseProperties.class)
class DatabaseConfig {
    @Bean
    @Primary
    DataSource dataSource(DatabaseProperties properties) {
        var config = new HikariConfig();
        config.setPoolName("line-business");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(properties.connectionTimeoutMs());
        return new HikariDataSource(config);
    }

    // 步骤 1 先累计落库失败数；步骤 3 的 Actuator 会提供指标端点和正式注册表。
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry persistenceMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
