# LINE Messaging API 服务骨架

JDK 17、Maven 3.6.3+、Spring Boot 3.5.16、LINE SDK 10.2.0。

## 运行

在仓库根目录的 PowerShell 中执行（先通过部署环境或本地会话设置真实凭证）：

```powershell
$env:LINE_CHANNEL_SECRET = '<你的 Channel Secret>'
$env:LINE_CHANNEL_TOKEN = '<你的 Channel Access Token>'
mvn spring-boot:run
```

配置位于 `src/main/resources/application.yml`，也可通过外部 Spring 配置设置
`line.bot.channel-secret` 和 `line.bot.channel-token`。YAML 占位符使用空默认值，
让缺失环境变量进入统一校验；缺失、空串或纯空白都会在创建客户端前终止启动。
错误仅列出缺失配置的名称及环境变量名称，不输出配置值。配置对象不生成包含凭证的 `toString()`。

## 验证

```powershell
mvn test
Invoke-RestMethod http://localhost:8080/health
```

健康接口返回 HTTP 200 和 `{"status":"UP"}`，表示应用已启动，不会调用 LINE 验证凭证有效性。
启动和自动测试也不会向 LINE 发送请求。

停止服务后，在新终端中不设置上述环境变量，执行 `mvn spring-boot:run`。
预期启动失败、进程退出码非零，报错包含：

```text
Missing required LINE configuration: line.bot.channel-secret (LINE_CHANNEL_SECRET), line.bot.channel-token (LINE_CHANNEL_TOKEN). Set non-blank environment variables or configuration properties.
```

也应分别验证仅缺一个配置和纯空白值。测试已覆盖这些情况及报错不包含另一项凭证。

## 文件与职责

- `pom.xml`：Web、LINE Messaging API 客户端及测试依赖，JDK 17 编译配置。
- `src/main/resources/application.yml`：端口 8080、凭证环境变量映射。
- `src/main/java/com/example/line/LineMessagingApplication.java`：启动入口。
- `src/main/java/com/example/line/config/LineProperties.java`：配置绑定与快速失败校验。
- `src/main/java/com/example/line/config/LineClientConfig.java`：创建 `MessagingApiClient` Bean。
- `src/main/java/com/example/line/controller/HealthController.java`：健康接口。
- `src/test/java/com/example/line/config/LineClientConfigTest.java`：配置校验和客户端装配测试。
- `src/test/java/com/example/line/HealthIntegrationTest.java`：真实 HTTP 健康接口测试。
- `.gitignore`：忽略构建、临时工具及本地凭证文件。

本阶段实现骨架；Webhook、Reply、Push 的业务处理将在后续步骤添加。

## SDK 核实来源

- Maven Central 实时元数据（本次查询 release=10.2.0）：https://repo.maven.apache.org/maven2/com/linecorp/bot/line-bot-messaging-api-client/maven-metadata.xml
- 官方项目：https://github.com/line/line-bot-sdk-java
- 10.2.0 官方发布源码：https://repo.maven.apache.org/maven2/com/linecorp/bot/line-bot-messaging-api-client/10.2.0/line-bot-messaging-api-client-10.2.0-sources.jar

已直接检查该版本源码，客户端为 `com.linecorp.bot.messaging.client.MessagingApiClient`，
通过 `MessagingApiClient.builder(channelToken).build()` 创建，未使用旧版 `LineMessagingClient`。
