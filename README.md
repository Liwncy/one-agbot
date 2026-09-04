# one-agbot

多平台适配网关 + SnailAI Agent。通道用 Adapter，智能交给 Agent（不做本地插件引擎）。

## 模块

| 模块 | 职责 |
|------|------|
| `one-common-*` | BOM + core/json/web/redis/log/mybatis/mcp |
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
mysql -u root -p one-agbot < script/sql/agbot_chat_message.sql

# PostgreSQL（可选）
# psql -U postgres -d one-agbot -f script/sql/postgres/postgres_one_agbot_ai.sql
```

2. 启动 SnailAI Server：

```bash
mvn -pl one-extend/one-snailai-server -am spring-boot:run
```

默认 `spring.profiles.active=dev`，短期记忆 `snail-ai.memory.short-term.store-type=db`。

3. 配置密钥（不要写进可提交的 yml）：

```bash
# 复制本地覆盖文件
cp one-boot/src/main/resources/application-local.yml.example \
   one-boot/src/main/resources/application-local.yml
# 编辑填入 Redis 密码、snail-ai.token
```

也可用环境变量：`REDIS_PASSWORD`、`SNAIL_AI_TOKEN`。  
`agbot.agent.default-agent-id` 等非密钥项仍在 `application.yml`。

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
   入站按微信 type 归一为通道 `MsgInfo`（图/视/音/链/引用等）。适配器会尝试把 PLATFORM 媒体下载升级为 `FILE`/`BASE64`（`media-resolve-enabled`，落盘 `media-store-path`）。Agent 按 `msgType` + `MediaRef` 识别。出站按契约映射（见 capabilities）。  
   默认 `session-require-activation=true`：会话未激活时不进 Agent（不自动注册用户/建会话）。私聊本人、群聊主人发指令后才聊；未激活的消息静默忽略。群聊激活后仍需 `@机器人` / 点名（或窗口内跟聊）。  
   点名成功后，同一用户默认有 `group-activation-window`（60s）免 @ 连续对话窗口。

4. 启停指令（无需 @；未激活时也能「开始」）：
   - 私聊本人 / 群主人：`开始` / `开机` / `启用` / `开` → 激活本会话
   - `结束` / `关机` / `停用` / `关` → 停用
   - `状态` → 是否已开  
   持久化：优先 Redis（`agbot:golem:session-active`）；不可用时落盘 `./data/golem/session-active.txt`。  
   若设 `session-require-activation=false`，则退回旧的群门禁（`group-disabled` + 主人群指令），私聊直接进 Agent。

## 写新 Adapter

通道契约是**能力上限**（见 [docs/channel-capabilities.md](docs/channel-capabilities.md)），不是某一家平台的交集。

1. 在 `one-adapter` 下新建模块，依赖 `one-kernel`。
2. 对照能力表实现 `ChatAdapter`（`reply` / `push` / `delMsg` / `bridge`），并用 `capabilities()` 声明本适配器真实子集。
3. 平台事件归一为 `MsgInfo`（含 `msgType` / `path` / `replyToMsgId`）后调用 `AdapterRuntime.receive`。
4. 出站按 `ReplyInfo.type` 映射；不支持则降级打日志，勿删 Kernel `MsgType`。
5. 登录/通讯录等平台专有能力放 `bridge()`；群门禁等产品策略不要塞进通道类型。
6. 在 `one-boot` 引入该模块依赖。

参考：`one-adapter-example`（内存覆盖上限类型 + `/push` `/delMsg`）、`one-adapter-golem`（按 OpenAPI 子集映射）。

## 配置要点

- `snail-ai.enabled=true`：注册 Agent Client（gRPC 执行器）；否则 Server 会报「没有可用的客户端实例」
- `snail-ai.open-api.*`：官方 OpenAPI Client（与 RuoYi `ruoyi-admin` 同前缀）
- `agbot.agent.*`：网关侧默认 agentId、是否异步
- `agbot.adapter.golem.*`：Golem 网关地址、Webhook 验签、会话激活（`session-require-activation`）等
- `agbot.kernel.max-message-age`：过旧消息丢弃
- 默认排除 DataSource/MyBatis；Redis 默认开启（Boot 4：`DataRedisAutoConfiguration`）。需密码时设 `REDIS_PASSWORD`；连不上则会话激活/群开关改用本地文件
- 本地联调需同时启动 `one-snailai-server` 与 `one-boot`（boot 兼做 OpenAPI 调用方 + Agent 执行器）
- 框架 MCP：`spring.ai.mcp.server` 在 boot 的 `/mcp` 暴露通道能力，工具以服务端列表为准。SnailAI 再绑一条 MCP（`script/sql/sai_mcp_one_agbot.sql`），与 cf-mcp-tools 并列。Docker 内 base_uri 用 `http://boot:8088`，本机用 `http://127.0.0.1:8088`。不要对公网暴露 `/mcp`。重新上传 `script/skills/mcp-tools/SKILL.md` 与 `script/skills/wechat-play/SKILL.md`。
