# one-agbot

多平台适配网关 + SnailAI Agent。通道用 Adapter，智能交给 Agent（不做本地插件引擎）。

## 模块

| 模块 | 职责 |
|------|------|
| `one-common-*` | BOM + core/json/web/redis/log/mybatis |
| `one-kernel` | 消息模型、`ChatAdapter` SPI、`AdapterRuntime`、`AgentBridge` 接口 |
| `one-agent` | SnailAI OpenAPI 桥接实现 |
| `one-adapter/*` | 平台适配器实现（MVP：`one-adapter-example`） |
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

## 写新 Adapter

1. 在 `one-adapter` 下新建模块，依赖 `one-kernel`。
2. 实现 `me.liwncy.agbot.kernel.api.adapter.ChatAdapter`（`init/start/stop/reply/push/delMsg`）。
3. 平台事件归一为 `MsgInfo` 后调用 `AdapterRuntime.receive`。
4. 在 `one-boot` 引入该模块依赖。

参考：`one-adapter/one-adapter-example`。

## 配置要点

- `snail-ai.enabled=true`：注册 Agent Client（gRPC 执行器）；否则 Server 会报「没有可用的客户端实例」
- `snail-ai.open-api.*`：官方 OpenAPI Client（与 RuoYi `ruoyi-admin` 同前缀）
- `agbot.agent.*`：网关侧默认 agentId、是否异步
- `agbot.kernel.max-message-age`：过旧消息丢弃
- 默认排除 DataSource/Redis 自动配置，便于无中间件本地起 example；生产请按需打开并配置
- 本地联调需同时启动 `one-snailai-server` 与 `one-boot`（boot 兼做 OpenAPI 调用方 + Agent 执行器）
