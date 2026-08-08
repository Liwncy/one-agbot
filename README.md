# one-agbot

多平台适配网关 + SnailAI Agent。通道用 Adapter，智能交给 Agent（不做本地插件引擎）。

## 模块

| 模块 | 职责 |
|------|------|
| `one-common-*` | BOM + core/json/web/redis/log/mybatis |
| `one-kernel` | 消息模型、`ChatAdapter` SPI、`AdapterRuntime`、`AgentBridge` 接口 |
| `one-agent` | SnailAI OpenAPI 桥接实现 |
| `one-adapter/*` | 平台适配器（`example` 联调、`golem` 微信个人号网关） |
| `one-boot` | 网关启动 |
| `one-extend/one-snailai-server` | SnailAI Server 独立进程 |

要求：**Java 21**、**Spring Boot 4.1.x**（与 SnailAI 1.1.1 对齐）、Maven 3.9+。

## 快速开始

1. 准备 MySQL：创建库后导入初始化脚本，并修改  
   `one-extend/one-snailai-server/src/main/resources/application-dev.yml` 中的数据源。

```bash
# MySQL
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS \`one-agbot\` DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p one-agbot < script/sql/one_agbot_ai.sql

# PostgreSQL（可选）
# psql -U postgres -d one-agbot -f script/sql/postgres/postgres_one_agbot_ai.sql
```

2. 启动 SnailAI Server：

```bash
mvn -pl one-extend/one-snailai-server -am spring-boot:run
```

默认 `spring.profiles.active=dev`，短期记忆 `snail-ai.memory.short-term.store-type=db`。

3. 修改 `one-boot/src/main/resources/application.yml` 中 `snail-ai.app-id` / `token`，以及 `agbot.agent.default-agent-id`（配置风格对齐 RuoYi）。

4. 启动网关：

```bash
mvn -pl one-boot -am spring-boot:run
```

5. 调用示例适配器：

```bash
curl -X POST http://127.0.0.1:8088/adapter/example/default/message \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"u1\",\"userName\":\"demo\",\"groupId\":\"0\",\"msg\":\"你好\"}"
```

同 `userId`/`groupId` 会复用 conversationId（默认内存映射；启用 Redis 后走 Redis）。

### Golem 适配器（微信个人号网关）

参照 xchatbot 的 wechat 通道，对接 [Golem](https://golem.apifox.cn)：

1. 在 `application.yml` 打开并配置：

```yaml
agbot:
  adapter:
    golem:
      enabled: true
      api-base-url: http://127.0.0.1:7080
      webhook-token: your_hmac_token   # 可空，空则不验签
      bot-wechat-id: wxid_xxx          # 机器人 wxid（回环过滤 + 群 @ 识别）
      bot-wechat-name: 小助手           # 群聊 @昵称 识别，建议配置
      owner-wechat-id: wxid_owner       # 主人 wxid，可在群内启停机器人
      group-require-mention: true      # 群聊仅 @ 才回复（默认 true）
```

2. 在 Golem 管理端把推送地址设为：

`http://<你的网关>:8088/adapter/golem/default/webhook`

3. 入站验签头：`x-signature` / `x-timestamp`（HMAC-SHA256(token, timestamp+body)）。  
   出站文本：`POST {api-base-url}/api/message/text`。  
   MVP 目前只处理文本；群聊在 `@机器人`、正文提及昵称、或 `atuserlist` 命中时才进 Agent，私聊仍全量回复。

4. 主人在群里可发（无需 @，停用后仍可开机）：
   - `开机` / `启用` / `开` → 启用本群
   - `关机` / `停用` / `关` → 停用本群
   - `状态` → 查看本群是否开启  
   群开关持久化：优先 Redis（`agbot:golem:group-disabled`）；Redis 不可用时落盘
   `./data/golem/group-disabled.txt`（重启不丢）。

## 写新 Adapter

1. 在 `one-adapter` 下新建模块，依赖 `one-kernel`。
2. 实现 `me.liwncy.agbot.kernel.api.adapter.ChatAdapter`（`init/start/stop/reply/push/delMsg`）。
3. 平台事件归一为 `MsgInfo` 后调用 `AdapterRuntime.receive`。
4. 在 `one-boot` 引入该模块依赖。

参考：`one-adapter/one-adapter-example`、`one-adapter/one-adapter-golem`。

## 配置要点

- `snail-ai.enabled=true`：注册 Agent Client（gRPC 执行器）；否则 Server 会报「没有可用的客户端实例」
- `snail-ai.open-api.*`：官方 OpenAPI Client（与 RuoYi `ruoyi-admin` 同前缀）
- `agbot.agent.*`：网关侧默认 agentId、是否异步
- `agbot.adapter.golem.*`：Golem 网关地址、Webhook 验签、是否启用
- `agbot.kernel.max-message-age`：过旧消息丢弃
- 默认排除 DataSource/MyBatis；Redis 默认开启（Boot 4：`DataRedisAutoConfiguration`）。需密码时设 `REDIS_PASSWORD`；连不上则群开关自动改用本地文件
- 本地联调需同时启动 `one-snailai-server` 与 `one-boot`（boot 兼做 OpenAPI 调用方 + Agent 执行器）
