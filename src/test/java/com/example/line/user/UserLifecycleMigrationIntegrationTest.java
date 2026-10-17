package com.example.line.user;

import com.example.line.common.persistence.PostgresIntegrationSupport;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

/** 在隔离旧版 schema 执行真实 V4→V5，确认保留数据且不伪造旧状态的接收顺序。 */
@SpringBootTest(properties={"line.bot.channel-secret=fictional-lifecycle-migration-secret","line.bot.channel-token=fictional-lifecycle-migration-access"})
class UserLifecycleMigrationIntegrationTest extends PostgresIntegrationSupport {
    @Autowired DataSource source;
    @Test void v5PreservesAllLegacyValuesAndRequiresCompleteOrderingFields()throws Exception{
        String schema="lifecycle_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("4").load().migrate();
        try(var connection=source.getConnection()){
            String previous=connection.getSchema();
            try{
                connection.setSchema(schema);var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                jdbc.update("INSERT INTO line_user(user_id,status,followed_at,updated_at) VALUES('Ufictional-lifecycle','FOLLOWING',now(),now())");
                jdbc.update("INSERT INTO chat_message(user_id,direction,message_type,content) VALUES('Ufictional-lifecycle','INBOUND','TEXT','{\"text\":\"完整虛構舊原文\"}')");
                var before=jdbc.queryForMap("SELECT * FROM line_user");var messages=jdbc.queryForList("SELECT * FROM chat_message");
                var flyway=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();flyway.migrate();
                var after=jdbc.queryForMap("SELECT * FROM line_user");before.forEach((key,value) -> assertThat(after.get(key)).isEqualTo(value));
                assertThat(after.get("lifecycle_received_at")).isNull();assertThat(after.get("lifecycle_sequence")).isNull();assertThat(after.get("lifecycle_event_id")).isNull();
                assertThat(jdbc.queryForList("SELECT * FROM chat_message")).isEqualTo(messages);
                assertThatThrownBy(() -> jdbc.update("UPDATE line_user SET lifecycle_received_at=now(),lifecycle_event_id='fictional-incomplete'"))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                flyway.migrate();assertThat(jdbc.queryForMap("SELECT * FROM line_user")).isEqualTo(after);
            }finally{connection.setSchema(previous);}
        }finally{new JdbcTemplate(source).execute("DROP SCHEMA "+schema+" CASCADE");}
    }
}
