# LINE Messaging API 接入服务

JDK 17、Maven 3.6.3+、Spring Boot 3.5.16、LINE SDK 10.2.0。

接口契约、完整收发 JSON、模拟联调与真实 LINE 对接步骤见 [接口文档](docs/接口文档.md)。
后续接口变更必须同步更新该文档及其模拟数据，维护规则见 [AGENTS.md](AGENTS.md)。

部署前的覆盖范围与剩余问题见 [自查报告](docs/自查报告.md)。

## 创建 LINE Channel 并取得凭证

当前 Messaging API Channel 的入口是官方账号管理后台：先创建 LINE Official Account，
启用 Messaging API 后自动生成 Channel，再到 Developers Console 配置；
不能直接在 Console 新建该类型 Channel。[官方创建流程](https://developers.line.biz/en/docs/messaging-api/getting-started/)

1. 登录 [LINE Official Account Manager](https://manager.line.biz/)，创建官方账号，已有账号可直接选择。
2. 在账号设置的 Messaging API 页面启用 API，按向导登记开发者资料并选择 Provider。
   Provider 关系不能随意迁移，使用本项目实际归属的 Provider。
3. 用同一账号登录 [LINE Developers Console](https://developers.line.biz/console/)，
   进入该 Provider，打开自动生成的 Messaging API Channel。
4. 在 **Basic settings → Channel secret** 取得 Secret，对应环境变量 `LINE_CHANNEL_SECRET`。
5. 在 **Messaging API → Channel access token (long-lived) → Issue** 生成 Token，
   对应 `LINE_CHANNEL_TOKEN`。长期 Token 方便首次联调；生产可按官方建议使用 v2.1，
   本项目没有自动签发/轮换 Token，过期或重签发后要更新运行环境并重启。
   [Token 官方说明](https://developers.line.biz/en/docs/basics/channel-access-token/)

这些值只放在运行环境或被忽略的本地配置，不放入 README、源码或截图。
Channel Secret 用于入站验签，Access Token 用于 SDK 出站，不能混用。

## 关闭自动回复与问候消息，配置 Webhook

先按下文启动服务，并准备可从公网访问的 HTTPS 地址。

1. 在 Channel 的 **Messaging API** 页找到 **Auto-reply messages** 和 **Greeting messages**，
   点击各自 **Edit** 跳转 Official Account Manager，在响应设置中将两项设为 Disabled/关闭。
   页面语言不同名称可能不同；保留 Webhook 开关。关闭这两项可避免平台自动消息与 echo 重复。
2. 回到 **Messaging API → Webhook settings → Webhook URL → Edit**，填写：
   `https://<公网域名>/line/webhook`，然后 **Update**。
3. 点击 **Verify**，预期显示 **Success**；随后打开 **Use webhook**。
4. 扫描该页机器人 QR code 添加好友，发送 `hello`，应收到一条 `hello` 回声。

Verify 只验证 URL 可用；本服务接受 `events=[]`，这一步不会发送 Reply。
真实文本收发还要靠第 4 步确认。[官方 Webhook 和自动消息设置](https://developers.line.biz/en/docs/messaging-api/building-bot/)

LINE 要求 Webhook 为 HTTPS，证书须受信任，自签名证书不适用；
本地服务继续用 HTTP，由隧道/反向代理终止 TLS。

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

## 本地调试：ngrok 或 cloudflared

先执行 `mvn spring-boot:run`，确认 `http://localhost:8080/health` 返回 UP。
以下二选一，在另一个终端运行并保持进程存活；这里只提供命令，不会自动创建公网隧道。

**ngrok**：按 [官方 Quickstart](https://ngrok.com/docs/share-localhost/quickstart) 安装 CLI、
登录账号并取得 ngrok authtoken。该 token 属于 ngrok，不能使用 LINE 凭证替代。

```powershell
ngrok config add-authtoken '<你的 ngrok authtoken>'
ngrok http 8080
```

复制终端显示的 HTTPS Forwarding 地址，例如 `https://<分配域名>.ngrok.app`，
在 Console 填写 `https://<分配域名>.ngrok.app/line/webhook`。
不要使用 OAuth、Basic Auth 或浏览器登录页拦截 Webhook，LINE 无法代替用户登录。
ngrok 的 Traffic Inspector 可能保存请求和响应，真实联调时检查其留存设置，不要分享包含 Token/用户信息的抓包。

**cloudflared**：按 [Cloudflare 官方文档](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) 安装后运行：

```powershell
cloudflared tunnel --url http://localhost:8080
```

复制生成的 `https://<随机域名>.trycloudflare.com`，填入
`https://<随机域名>.trycloudflare.com/line/webhook`。Quick Tunnel 用于临时开发，
域名重建后会变化；变更后重新 Update 和 Verify。不要加需要交互登录的 `--allowed-mail`。

Docker Compose 将端口映射到宿主机 127.0.0.1:8080，宿主机上的隧道同样使用上述命令。
隧道会转发本服务其他路径，公开调试期间保持 `LINE_INTERNAL_PUSH_ENABLED=false`。

排查：

| 表现 | 优先检查 |
| --- | --- |
| Verify 连接失败/502 | 应用是否启动、隧道是否还在、端口是否为 8080 |
| Verify 返回 404 | URL 是否包含 `/line/webhook`，是否仍用旧隧道域名 |
| Verify 返回 400 | Channel Secret 是否属于当前 Channel，代理是否修改原始请求字节 |
| Verify 成功但不收到消息 | Use webhook、是否加好友、当前 URL、是否真的进行了文本发送 |
| 收到两条消息 | Official Account Manager 的自动回复/问候消息是否关闭 |
| 入站 200 但没有回声 | LINE send failed、Token 有效性、Reply 排队超时、线程池拒绝日志 |

## Docker 与 Docker Compose

需要运行中的 Docker Engine；Windows Docker Desktop 使用 Linux containers。
Dockerfile 多阶段构建：JDK 17 + Maven 执行 `verify`（包含测试），运行层只放 JRE 17 和 JAR，
以 UID/GID 10001 非 root 用户运行。由于测试读取文档模拟 JSON，构建阶段会复制 `docs/`。

Compose 从当前进程环境或仓库根目录 `.env` 注入密钥。首次可执行：

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
# 在本地编辑 .env，填写 LINE_CHANNEL_SECRET 和 LINE_CHANNEL_TOKEN。
# 已存在的 .env 保留不覆盖。
docker compose config --quiet
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://localhost:8080/health
docker compose logs --tail 100 line-messaging
```

`.env` 已被 Git 和 Docker 构建上下文忽略；`.env.example` 只包含空配置项。
Compose 缺少必需变量会直接失败，不会用默认假密钥启动。
`mvn spring-boot:run` 不自动加载 `.env`，直接运行 Maven 时请设置进程环境变量。
不要把 `docker compose config` 的展开配置或 `docker inspect` 输出发给他人，
环境变量注入仍可被有 Docker 管理权限的人读取。

单独构建/运行（先在当前终端设置两个 LINE 环境变量）：

```powershell
docker build -t line-messaging:local .
docker run --rm --name line-messaging -p 127.0.0.1:8080:8080 -e LINE_CHANNEL_SECRET -e LINE_CHANNEL_TOKEN line-messaging:local
```

Compose 默认关闭内部 Push、只映射本机端口，文件系统只读，`/tmp` 使用临时挂载；
内容下载若要持久化，需要另行配置可写存储，本示例没有自动落盘。
密钥不作为 Docker ARG、构建 ENV 或镜像文件传入。
Dockerfile/Compose 本身不提供公网 TLS，仍需 HTTPS 隧道或反向代理。

停止服务：

```powershell
docker compose down
```

## 测试覆盖与验证结果

```powershell
mvn verify
```

本次 JDK 17 构建：**55 个测试全部通过**，可执行 Spring Boot JAR 已生成。
Compose 配置校验通过，且缺少密钥时拒绝展开配置。
本机 Docker 引擎未运行，因此本次未验证镜像构建及容器启动；
上述 Docker 命令需在运行中的引擎上执行。

| 测试 | 覆盖内容 |
| --- | --- |
| `SignatureVerifierTest` | RFC 4231 独立向量、中文、换行/空格/篡改、错 Secret、缺失/非法签名、并发验签 |
| `MessageEventHandlerTest` | mock MessagingApiClient，文本 echo、四种暂不支持消息、standby、缺失消息、SDK 失败 |
| `LifecycleEventHandlersTest` | 真实 Follow/Unfollow/Postback Handler 的 userId/eventId 日志和敏感字段不输出 |
| `InMemoryEventDeduplicatorTest` | 并发原子抢占、成功后 TTL、精确过期边界、处理中不失效、失败释放、容量限制 |
| `WebhookServiceTest` | 重投递、策略分发、异步、过期 Reply、异常释放、线程池拒绝 |
| `DocumentedFlowIntegrationTest` | 真实入站 HTTP → SDK → 本地 Stub，Reply/Push 数据、重试同 Key、409、下载与日志检查 |

生命周期 Handler 不依赖 MessagingApiClient，测试直接验证它们的日志副作用；
需要调用 SDK 的 Message Handler 使用真实消息封装层并 mock SDK，不发往真实 LINE。
完整潜在问题及覆盖状态见 [自查报告](docs/自查报告.md)。

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

已实现 Webhook、文本 Reply Echo、Push、消息构造、事件分发与内容下载示例。
`POST /internal/push` 默认关闭，启用需要 `LINE_INTERNAL_PUSH_ENABLED=true`
及独立的 `LINE_INTERNAL_PUSH_TOKEN`，请求头为 `X-Internal-Token`。

## SDK 核实来源

- Maven Central 实时元数据（本次查询 release=10.2.0）：https://repo.maven.apache.org/maven2/com/linecorp/bot/line-bot-messaging-api-client/maven-metadata.xml
- 官方项目：https://github.com/line/line-bot-sdk-java
- 10.2.0 官方发布源码：https://repo.maven.apache.org/maven2/com/linecorp/bot/line-bot-messaging-api-client/10.2.0/line-bot-messaging-api-client-10.2.0-sources.jar

已直接检查该版本源码，客户端为 `com.linecorp.bot.messaging.client.MessagingApiClient`，
通过 `MessagingApiClient.builder(channelToken).build()` 创建，未使用旧版 `LineMessagingClient`。

实际 Bean 创建时设置 `usingDefaultLogger(false)`，并关闭 SDK wire logger，防止默认报文日志记录凭证和消息内容。

## Webhook 接收

`POST /line/webhook` 从 `@RequestBody byte[]` 获取原始字节。采用 JDK `Mac/HmacSHA256`
计算签名，Base64 解码请求头后通过 `MessageDigest.isEqual` 恒定时间比较摘要。
未使用 SDK Spring Boot starter。缺失、非法 Base64 或不匹配的 `X-Line-Signature` 返回 400，
不会解析或提交事件。验签通过后仅提交线程池，立即返回 200；JSON 解析、分发、回复全部在工作线程执行。
不会记录原始请求体、文本内容、Secret、Access Token 或 Reply Token。

处理方式：

- `MessageEvent/TextMessageContent`：使用 SDK `ReplyMessageRequest` 原样回复文本。
- 图片、贴图、位置、文件及其他消息：记录消息类型、messageId、eventId，回复“暂不支持”。
- `FollowEvent/UnfollowEvent`：记录 userId 和 eventId。
- `PostbackEvent`：保留独立 Handler 入口，可在其中读取 `event.postback().data()`。
- `standby` 模式不发送回复；未知事件类型记录日志。
- 新增事件：实现 `EventHandler<E>`，声明 `eventType()` 并标注 `@Component`，自动注册，
  无需改 controller。重复注册相同事件类型会启动失败。

### 线程池与幂等语义

`application.yml` 的 `line.webhook` 配置：4 个固定工作线程、队列容量 256、
去重 TTL 24 小时、最多 10000 个去重项、下载上限 10 MiB。

队列满或应用关闭时，拒绝策略记录 ERROR 并抛异常，由接入服务捕获；不会在请求线程执行任务。
按本次“立即返回 200”的要求，此时仍返回 200，**被拒绝的任务不会被处理**。
需要保证接收可靠性时，应改为持久化消息队列成功写入后确认，或允许过载返回非 2xx。
同样，验签通过但 JSON 无法解析时，异步记录日志，HTTP 已返回 200。

`EventDeduplicator` 提供 `tryAcquire/complete/release`。内存实现原子抢占
`webhookEventId`，处理中不被 TTL 清理，成功后保留 TTL；处理失败释放，以便后续投递重试。
`isRedelivery=true` 仅用于日志：第一次看到的重投递仍处理，已成功或正在处理的 ID 跳过。
缓存满时拒绝新 ID 并记录处理失败日志，不驱逐仍有效的去重记录。
缓存仅限单进程，重启清空、多个实例不共享，TTL 到期后相同事件可再次处理。
HTTP 200 后的异步失败不会自动触发 LINE 重投递；当前没有后台重试。
Push 单次调用会对 5xx/网络错误做有限指数退避重试，并从首次请求起复用 Retry Key；
Reply 不自动重试，消息在本次入站队列中等待超过 15 秒时跳过回复。
远端已执行但客户端超时的情况仍可能重复产生副作用，不能声称 exactly-once。
换 Redis 时替换该接口 Bean，实现原子抢占、成功 TTL、处理中租约及安全释放。

### 签名请求验证（PowerShell + curl）

启动应用后，在另一个已设置相同 `LINE_CHANNEL_SECRET` 的终端执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/Send-TestWebhook.ps1
# 预期 HTTP/1.1 200，后台记录 FollowEvent / Utestuser

powershell -ExecutionPolicy Bypass -File scripts/Send-TestWebhook.ps1 -InvalidSignature
# 预期 HTTP/1.1 400，不处理事件
```

脚本读取环境变量中的 Secret，对 `scripts/webhook-sample.json` 的原始文件字节签名，
再调用 `curl.exe --data-binary @文件` 发送完全相同的字节（保留换行）。
可以用 `-BodyPath` 指定其他 JSON，`-Url` 指定服务地址。
重复运行正确签名请求会返回 200，但相同 eventId 只处理一次。
把示例的 `isRedelivery` 改为 true 后重新签名，可验证相同 ID 被去重；
更换 eventId 后会处理。测试 FollowEvent 不调用 LINE 发送 API。
用文本事件验证真实 Reply 时，需要 LINE 实际投递的有效 Reply Token。

### 图片/文件内容下载示例

已创建 `MessagingApiBlobClient` Bean，使用 SDK 的
`getMessageContent(messageId)` 请求 `https://api-data.line.me/v2/bot/message/{messageId}/content`。
`LineContentService.downloadTo` 演示通过 SDK 下载到调用方的输出流，
流式复制、限制最大字节数并始终关闭上游流；下游流由调用方关闭。
不自动下载每张图片或文件，避免测试请求产生外部调用。
可以在异步业务 Handler 中注入该服务，使用图片/文件事件的 `event.message().id()`：

```java
// lineContentService 由构造器注入；目录由部署配置提供并预先创建。
// 使用服务端生成的文件名，不使用用户提供的 fileName。
Path destination = downloadDirectory.resolve(UUID.randomUUID() + ".bin");
try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
    long bytes = lineContentService.downloadTo(event.message().id(), output);
} catch (Exception e) {
    Files.deleteIfExists(destination); // 删除失败或超限产生的部分文件
    throw e;
}
```

该调用需要有效 Channel Access Token 和仍可下载的真实 messageId。
图片 contentProvider 为 external 时不走 LINE Content API，本示例不抓取外部 URL。
超限、网络失败、过期内容均向调用方抛异常。

### 本次改动文件清单

- `pom.xml`：新增 `line-bot-webhook`、显式声明 `line-bot-jackson`，版本保持 10.2.0。
- `src/main/resources/application.yml`：线程池、缓存、下载限制。
- `src/main/java/com/example/line/config/LineClientConfig.java`：新增 Blob 客户端。
- 同目录 `WebhookConfig.java`、`WebhookProperties.java`：有界线程池和配置。
- `src/main/java/com/example/line/controller/WebhookController.java`：验签与快速确认。
- `src/main/java/com/example/line/handler/`：`EventHandler.java`、`MessageEventHandler.java`、
  `FollowEventHandler.java`、`UnfollowEventHandler.java`、`PostbackEventHandler.java`。
- `src/main/java/com/example/line/service/`：`SignatureVerifier.java`、`WebhookService.java`、
  `EventDispatcher.java`、`EventDeduplicator.java`、`InMemoryEventDeduplicator.java`、
  `LineMessageService.java`、`LineContentService.java`。
- `src/test/java/com/example/line/controller/WebhookControllerTest.java`：MockMvc 正确/错误/缺失/非法签名。
- `src/test/java/com/example/line/config/WebhookConfigTest.java`：真实有界线程池饱和拒绝及日志。
- `src/test/java/com/example/line/service/`：`WebhookServiceTest.java`、
  `InMemoryEventDeduplicatorTest.java`、`LineContentServiceTest.java`、`LineMessageServiceTest.java`。
- `scripts/Send-TestWebhook.ps1`、`scripts/webhook-sample.json`：curl 签名测试方法。
- `README.md`：运行、验证、扩展机制、下载示例和交付语义。

验证命令：`mvn test`。MockMvc、策略分发、异步隔离、线程池拒绝、并发去重、缓存 TTL、
处理失败后释放、重投递、SDK Reply 构造、下载及资源关闭均通过本地测试，不访问真实 LINE。

官方资料：

- 签名：https://developers.line.biz/en/docs/messaging-api/verify-webhook-signature/
- Webhook 与重投递：https://developers.line.biz/en/docs/messaging-api/receiving-messages/
- 内容下载：https://developers.line.biz/en/reference/messaging-api/#get-content

## 消息发送与真实 echo 验收

`LineMessageService` 提供 `replyText/replyMessages/pushText/pushMessages`。
每次封装校验 1..5 条消息；`LineMessages` 提供文本、贴图、图片、Flex 卡片和 Quick Reply 工厂。
Reply 不计入月度消息额度，业务优先使用 Reply；Push 会消耗额度。
Push 默认总尝试 3 次，等待每次最多 3s，退避 200ms、400ms；4xx 不重试，
409 带 accepted-request-id 视为已接受。详细请求、错误体和配置见接口文档。

真实联调：

1. 配置真实 `LINE_CHANNEL_SECRET`、`LINE_CHANNEL_TOKEN`，运行 `mvn spring-boot:run`。
2. 配置公网 HTTPS Webhook URL `https://<域名>/line/webhook` 并启用 Webhook，Verify 应成功。
3. 用 LINE 添加机器人为好友，发送 `hello`，验收收到完全相同的 `hello`。
4. 若收到多条，检查官方账号自动回复设置；若无回声，检查签名与 LINE send failed 日志。

本地双向 SDK 模拟：`mvn -Dtest=DocumentedFlowIntegrationTest test`；
全部自动测试：`mvn test`。模拟不替代真实 Token、用户与公网环境的验收。
