# P0/P1 入站、AI、持久化与日志验证

P0 四步能力与规则已整合，当前范围统一见 [接口文档完成状态](../../接口文档.md#24-p0-完成范围与后续边界)。

本文和所链接的 JSON 全部是虚构模拟数据，不包含真实用户或真实凭证。
示例按方向分类：当前目录为入站 Webhook，出站 [Reply](../reply/) 与 [Push/内部推送](../push/) 分别存放。
已实现持久化验证复用现有完整 [文本请求](./webhook-text.json)、[重投递请求](./webhook-redelivery.json)、
[关注请求](./webhook-follow.json)、[取消关注请求](./webhook-unfollow.json) 和 [hello 请求](./webhook-hello.json)。
JSON 字段和 HTTP 响应体保持不变；步骤 2 新增 X-Trace-Id 响应头，见 [完整虚构响应](webhook-response.http)。
不在示例中填写数据库连接地址/账号/密码。

1. 启动 Docker Engine，在仓库根目录执行 `mvn clean test`。
2. DocumentedFlowIntegrationTest 使用临时 PostgreSQL/Redis、固定模型替身与本地 LINE Stub，验证文本入站、Reply 数据、完整用户标识和文本落库、凭证字段排除、响应头及重投递幂等。
3. ChatPersistenceIntegrationTest 验证最近历史、入站唯一索引、完整业务内容、清理开关与单批上限。
4. UserPersistenceIntegrationTest 验证取消关注保留聊天、重新关注恢复状态，以及手动删除覆盖所有 Purger。
5. 本地运行时先由运行环境提供 `DATABASE_URL`、`DATABASE_USERNAME`、`DATABASE_PASSWORD` 、LINE 两项凭证、OPENAI_API_KEY、REDIS_HOST 与应用/Channel 唯一 AI_NAMESPACE，再执行 `mvn spring-boot:run`。不在此处填写连接值。
6. LoggingFlowIntegrationTest 构造虚构 Token、签名、用户与原文，验证请求 → Webhook → Reply → 落库失败的同一 trace，以及内部 Push 重试的凭证禁记；所有 LINE 调用均为 mock。
7. LocalReadableLogIntegrationTest 验证 local profile 同时输出 JSON stdout 和额外易读文件；测试文件放系统临时目录，项目运行文件默认放 logs/。
8. EventLogIntegrationTest、RecordQueueTest 验证事件字段白名单、独立日志池、批写幂等、满队列丢弃、一次重试/stdout 兜底和停机刷队列。
9. AuditRetentionIntegrationTest、AuditServiceTest 验证审计、清理和失败隔离；LoggingFlowIntegrationTest 额外验证日志库故障不改变推送 200、发送失败指标与失败审计。
10. ManagementIntegrationTest 验证回环管理端口只开放 health/info/metrics，业务端口不暴露管理接口；DrainingTaskExecutorTest 验证真实 Spring 停机顺序。

默认 `CHAT_CLEANUP_ENABLED=false`，不会因模拟数据的事件时间较旧而清理数据。
需要清理时显式启用并设置 `CHAT_RETENTION_DAYS`；完整配置见 [接口文档](../../接口文档.md)。
事件与审计清理同样默认关闭，分别通过 EVENT_LOG_CLEANUP_ENABLED、AUDIT_LOG_CLEANUP_ENABLED 显式启用。
全链路 trace、结构化日志、event_log/audit_log、独立日志池与指标 HTTP 端点均已实现。
下面的响应和日志只用于解释字段关系，时间与标识全部虚构：

```json
{"ts":"2026-10-15T04:00:00Z","level":"INFO","service":"line-messaging-service","env":"local","version":"0.0.1-SNAPSHOT","instance":"fictional-instance","trace_id":"11111111-1111-4111-8111-111111111111","event":"user.message.received","user_id":"Uaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","logger":"com.example.line.webhook.handler.MessageEventHandler","thread":"line-webhook-1","message":"LINE user message received","webhook_event_id":"01K7B1N0000000000000000001","message_type":"TEXT","message_id":"537629183902001","text_length":23,"content":{"type":"text","id":"537629183902001","text":"你好，我想查询订单 A202610130001"},"outcome":"SUCCESS"}
```

对应 stdout 中按 trace_id 查找 user.message.received、line.reply.requested、line.reply.sent 与 persist.failed（如有）；
本地易读日志使用同一 trace。数据库完整虚构行见 [event-log.json](event-log.json)，
内部推送审计行见 [audit-log.json](../push/audit-log.json)，均仅示例、没有真实用户记录。
这些表行示例不作为 Webhook 或 LINE 出站请求发送；实际 id、ts、trace_id、instance 与 source_ip 由运行服务生成。

DocumentedFlowIntegrationTest 按文档请求发起完整收发后，将实际表行中的固定业务字段与上述示例比较，
并核对原 HTTP trace、完整用户与消息及凭证禁记边界。按响应头检索数据库：

```sql
SELECT ts, event, user_id, detail FROM event_log
WHERE trace_id = '11111111-1111-4111-8111-111111111111' ORDER BY ts;
SELECT created_at, event, operator, target_user_id, outcome, detail FROM audit_log
WHERE trace_id = '33333333-3333-4333-8333-333333333333' ORDER BY created_at;
```

本机直接启动应用后可以执行（已实现；以下是手动验证步骤）：

```powershell
Invoke-RestMethod http://127.0.0.1:9090/actuator/health
Invoke-RestMethod http://127.0.0.1:9090/actuator/metrics/webhook.requests
Invoke-RestMethod http://127.0.0.1:9090/actuator/metrics/log.dropped
```

Compose 不映射管理端口；不要把 9090 端口通过隧道暴露公网。stdout 中 persist.failed 不再进入事件表。

## P1 已实现流程与虚构结果

`webhook-text.json` / `webhook-hello.json` 的 active USER 文本现在进入人设 AI。DocumentedFlowIntegrationTest 的模型替身固定返回原文与 10/10 token、1ms，便于保留原有精确 Reply 断言；真实模型回复不保证相同文本或用量。[ai-result.json](../reply/ai-result.json) 是这条虚构结果的完整业务字段，不是 LINE HTTP 请求，也不是线上数据。

DocumentedFlowIntegrationTest 同时校验 user 消息只出现一次、负责人原人设占位符已替换，以及出站来源、类别、配对和用量与 JSON 一致。AiFlowIntegrationTest 覆盖正常多轮、兜底/超长/未完成、私聊范围、Reply 结果未知、已发送后缓存失败、依赖暂停、限流、输入预算、迟到结果、删除并发与完整数据/凭证边界。OpenAiAdapterTest 使用本地 HTTP Stub 验证真实官方 SDK 的参数、4xx 不重试、5xx 一次重试、超时及禁记凭证；不访问 OpenAI。

本地联调按接口文档 11.1 配置外部服务，仅运行一份 Java 进程。测试 Channel 添加好友后发送私聊文本，期望收到小晴人设回复；继续发第二条确认前文可用，群聊仍回显，standby 不回复。按 stdout 中对应 http.request.completed 的 trace_id 查询完整模型请求 → 生成结果 → LINE 接受 → 异步记录；HTTP 响应为 200 不等于生成或发送成功。

```sql
SELECT event, model, input_tokens, output_tokens, detail FROM event_log
WHERE trace_id = '11111111-1111-4111-8111-111111111111' ORDER BY ts;
SELECT direction, source_type, reply_kind, in_reply_to_message_id, correlation_id,
       model_name, input_tokens, output_tokens, latency_ms, content
FROM chat_message WHERE user_id = 'Uaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' ORDER BY id;
```

所有 SQL 标识仍为虚构占位值。部署方须人工验证真实模型权限、预算内响应率和费用；本地测试不产生真实模型调用或消息发送费用。Redis/数据库删除与模型迟到不会补发原消息，数据保留与清理仍沿用默认关闭策略。
