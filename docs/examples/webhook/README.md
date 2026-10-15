# P0 步骤 1 入站与持久化验证

本文和所链接的 JSON 全部是虚构模拟数据，不包含真实用户或真实凭证。
已实现持久化验证复用现有完整 [文本请求](./webhook-text.json)、[重投递请求](./webhook-redelivery.json)、
[关注请求](./webhook-follow.json)、[取消关注请求](./webhook-unfollow.json) 和 [hello 请求](./webhook-hello.json)。
JSON 字段和 HTTP 请求响应没有改变，不新增虚构的数据库连接地址/账号/密码示例。

1. 启动 Docker Engine，在仓库根目录执行 `mvn clean test`。
2. DocumentedFlowIntegrationTest 使用临时 PostgreSQL 与本地 LINE Stub，验证文本入站、Reply 数据、完整用户标识和文本落库、凭证字段排除及重投递幂等。
3. ChatPersistenceIntegrationTest 验证最近历史、入站唯一索引、完整业务内容、清理开关与单批上限。
4. UserPersistenceIntegrationTest 验证取消关注保留聊天、重新关注恢复状态，以及手动删除覆盖所有 Purger。
5. 本地运行时先由运行环境提供 `DATABASE_URL`、`DATABASE_USERNAME`、`DATABASE_PASSWORD` 和 LINE 两项凭证，再执行 `mvn spring-boot:run`。不在此处填写连接值。

默认 `CHAT_CLEANUP_ENABLED=false`，不会因模拟数据的事件时间较旧而清理数据。
需要清理时显式启用并设置 `CHAT_RETENTION_DAYS`；完整配置见 [接口文档](../../接口文档.md)。
事件/审计记录和全链路 trace 响应头尚未实现；不要把未来步骤的示例当作当前契约。
