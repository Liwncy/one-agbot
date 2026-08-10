# 通道能力契约

契约按无界能力最全的适配器（`GolemPlus` / `wxGolem` 消息面）做**上限**；弱适配器用 `ChatAdapter.capabilities()` 声明子集并降级，不得反向删减 Kernel 类型。

## 三层边界

| 层级 | 职责 | 示例 |
|------|------|------|
| **通道契约**（`one-kernel`） | 跨平台消息模型与收发 SPI | `MsgInfo` / `ReplyInfo` / `reply`·`push`·`delMsg` |
| **适配器**（`one-adapter-*`） | 平台协议 ↔ 契约映射；`Bridge` 平台专有 API | HMAC、JSON 解析、`msgId` 编解码、登录/通讯录 |
| **业务**（Agent / 未来 biz） | 何时回、回什么、产品门禁 | 群 @ 策略、主人开机、Skill、历史 |

群 @ / 开机停机等属于产品策略：可暂留适配器本地配置，**不是** `MsgType`，也不抬进 Kernel 门禁。

## 出站 / 入站类型上限（`MsgType`）

| type | 主要字段 | 说明 |
|------|----------|------|
| `text` | `msg`, `remind` | `@` 列表（wxid，逗号分隔或 List 序列化进字段） |
| `image` | `path`, `msg`=caption | |
| `video` | `path`, `msg`, `extra.thumb`, `extra.duration` | |
| `audio` | `path`；入站 `voice` 归一为 `audio` | |
| `file` | `path`, `msg`=文件名 | |
| `emoji` | `path` 或 `extra.md5` | |
| `link` | `title`, `msg`=desc, `url`/`path`, `extra.thumb` | |
| `card` | `extra.cardUsername` 等 | |
| `app` | `msg`=xml, `extra.appType` | 含引用消息等 |
| `position` | `extra.lat/lon/label/poiName/scale` | |
| `forward` | `msg`=xml, `extra.forwardType` | |

格式富文本：`type=text` + `extra.parseMode=markdown|html`（不单独占 MsgType）。

## `MsgInfo` / `ReplyInfo` 一等字段

- 身份：`platform`, `accountId`, `userId`, `userName`, `groupId`（`"0"`=私聊）, `groupName`
- 内容：`msg`, `msgId`, `msgType`/`type`, `path`
- 引用：入站 `replyToMsgId`；出站 `toMsgId`
- 出站扩展一等：`remind`, `title`, `url`
- 袋子：`extra`（见下）、`createTime` / `fromType`

## `extra` 键约定（`ChannelExtraKeys`）

| 键 | 用途 |
|----|------|
| `parseMode` | `markdown` / `html` |
| `mentionIds` | 入站被 @ 的用户列表 |
| `quoteContent` / `quoteMsgType` | 引用原文与原类型 |
| `thumb` / `duration` / `md5` / `format` | 媒体附属 |
| `cardUsername` / `cardNickname` / `cardAlias` | 名片 |
| `appType` / `forwardType` | app / forward |
| `lat` / `lon` / `label` / `poiName` / `scale` | 位置 |
| `friendId` / `botId` | TG 类路由 |

## 生命周期 SPI

| 方法 | 语义 |
|------|------|
| `reply` | 会话内回复，可带 `toMsgId`；返回可撤回的 `msgId` |
| `push` | 主动触达（无入站上下文）；Runtime 必须调用 `adapter.push` |
| `delMsg` | 按通道 `msgId` 撤回；不支持则 capabilities 关闭并 no-op |
| `bridge` | 登录/通讯录/群管理等平台逃逸舱，**不保证**跨适配器 |

## 写新 Adapter

1. 对照本表实现映射；在 `capabilities()` 声明真实子集。
2. 平台事件归一为 `MsgInfo` 后 `AdapterRuntime.receive`。
3. 出站按 `ReplyInfo.type` 分支；不支持则打日志降级，勿删 Kernel 常量。
4. 平台专有能力放 `bridge()`，勿塞进一等字段。

参考实现：`one-adapter-example`（内存覆盖上限类型）、`one-adapter-golem`（按 OpenAPI 子集映射）。
