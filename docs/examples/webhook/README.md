# P0 入站、持久化与日志验证

本文和所链接的 JSON 全部是虚构模拟数据，不包含真实用户或真实凭证。
示例按方向分类：当前目录为入站 Webhook，出站 [Reply](../reply/) 与 [Push/内部推送](../push/) 分别存放。
已实现持久化验证复用现有完整 [文本请求](./webhook-text.json)、[重投递请求](./webhook-redelivery.json)、
[关注请求](./webhook-follow.json)、[取消关注请求](./webhook-unfollow.json) 和 [hello 请求](./webhook-hello.json)。
JSON 字段和 HTTP 响应体保持不变；步骤 2 新增 X-Trace-Id 响应头，见 [完整虚构响应](webhook-response.http)。
不在示例中填写数据库连接地址/账号/密码。

1. 启动 Docker Engine，在仓库根目录执行 `mvn clean test`。
2. DocumentedFlowIntegrationTest 使用临时 PostgreSQL 与本地 LINE Stub，验证文本入站、Reply 数据、完整用户标识和文本落库、凭证字段排除、响应头及重投递幂等。
3. ChatPersistenceIntegrationTest 验证最近历史、入站唯一索引、完整业务内容、清理开关与单批上限。
4. UserPersistenceIntegrationTest 验证取消关注保留聊天、重新关注恢复状态，以及手动删除覆盖所有 Purger。
5. 本地运行时先由运行环境提供 `DATABASE_URL`、`DATABASE_USERNAME`、`DATABASE_PASSWORD` 和 LINE 两项凭证，再执行 `mvn spring-boot:run`。不在此处填写连接值。
6. LoggingFlowIntegrationTest 构造虚构 Token、签名、用户与原文，验证请求 → Webhook → Reply → 落库失败的同一 trace，以及内部 Push 重试的凭证禁记；所有 LINE 调用均为 mock。
7. LocalReadableLogIntegrationTest 验证 local profile 同时输出 JSON stdout 和额外易读文件；测试文件放系统临时目录，项目运行文件默认放 logs/。

默认 `CHAT_CLEANUP_ENABLED=false`，不会因模拟数据的事件时间较旧而清理数据。
需要清理时显式启用并设置 `CHAT_RETENTION_DAYS`；完整配置见 [接口文档](../../接口文档.md)。
全链路 trace 响应头与结构化日志已实现；event_log/audit_log、独立日志池与指标 HTTP 端点尚未实现（步骤 3）。
下面的响应和日志只用于解释字段关系，时间与标识全部虚构：

```json
{"ts":"2026-10-15T04:00:00Z","level":"INFO","service":"line-messaging-service","env":"local","version":"0.0.1-SNAPSHOT","instance":"fictional-instance","trace_id":"11111111-1111-4111-8111-111111111111","event":"user.message.received","user_id":"Uaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","logger":"com.example.line.webhook.handler.MessageEventHandler","thread":"line-webhook-1","message":"LINE user message received","webhook_event_id":"01K7B1N0000000000000000001","message_type":"TEXT","message_id":"537629183902001","text_length":23,"content":{"type":"text","id":"537629183902001","text":"你好，我想查询订单 A202610130001"},"outcome":"SUCCESS"}
```

对应 stdout 中按 trace_id 查找 user.message.received、line.reply.requested、line.reply.sent 与 persist.failed（如有）；
本地易读日志使用同一 trace。数据库 event_log 的联合查询待步骤 3 接入后补充。
