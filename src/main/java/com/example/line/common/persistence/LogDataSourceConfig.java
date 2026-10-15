package com.example.line.common.persistence;

import com.example.line.common.logging.recording.RecorderProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 复用外部连接配置装配独立日志连接池；不占业务池，不把日志连接失败升级为业务启动失败。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecorderProperties.class)
class LogDataSourceConfig {
    @Bean
    DataSource logDataSource(DatabaseProperties database, RecorderProperties recorder) {
        var config = new HikariConfig();
        config.setPoolName("line-log");
        config.setJdbcUrl(database.url());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(recorder.poolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(recorder.connectionTimeoutMs());
        // 日志库不可用时保留 stdout 兜底，不能卡住即时回复服务的启动。
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
