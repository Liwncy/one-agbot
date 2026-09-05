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
| `audio` | `path`；`extra.duration` / `extra.format`；入站 `voice` 归一为 `audio` | 出站语音；时长由适配器换算 |
| `file` | `path`, `msg`=文件名 | |
| `emoji` | `path` 或 `extra.md5` | |
| `link` | `title`, `msg`=desc, `url`/`path`, `extra.thumb` | |
| `music` | `title`, `msg`=歌手, `url`=页面, `extra.dataUrl`, `extra.thumb` | 出站；适配器拼平台结构，无音频可降级为 `link` |
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

## 媒体传输形态（`MediaForm` / `MediaRef`）

契约给适配器多种传输形式，按平台能力选用；可用工厂：`MediaRef.url/file/base64/platform`。

| form | 主数据放哪 | 说明 |
|------|------------|------|
| `URL` | `path`（可同步 `extra.mediaUrl`） | HTTP(S) 等可拉取地址 |
| `FILE` | `path` | 本地文件路径 |
| `BASE64` | `extra.mediaBase64` + `mediaMime` | 二进制已在消息内 |
| `PLATFORM` | `extra.mediaPlatformId` 或暂存 `path` | 平台原生 id（CDN/aes 等）；适配器可再升级为 URL/FILE/BASE64 |

可选：`extra.mediaForm`、`mediaMime`、`mediaSize`。未显式标注时，`MediaRef.from` 会按 path/内容推断。

出站媒体同样可用上述形态写入 `ReplyInfo`。

## `extra` 键约定（`ChannelExtraKeys`）

| 键 | 用途 |
|----|------|
| `parseMode` | `markdown` / `html` |
| `mentionIds` | 入站被 @ 的 wxid 列表 |
| `mentions` | 入站被 @ 的人 `[{seq, id, name, avatar}]`，seq 从 1 起 |
| `quoteContent` / `quoteMsgType` | 引用原文与原类型 |
| `thumb` / `duration` / `md5` / `format` / `dataUrl` | 媒体附属；`dataUrl` 为音乐卡音频直链 |
| `mediaForm` / `mediaUrl` / `mediaBase64` / `mediaPlatformId` / `mediaMime` / `mediaSize` | 媒体传输 |
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

参考实现：`one-adapter-example`（内存覆盖上限类型）、`one-adapter-golem`（入站全类型归一 + 出站按 OpenAPI 子集映射）。

## Agent 边界

- 通道契约收全量 `MsgType`（含 `video`），并提供多种 `MediaForm`。
- **适配器**选用/升级传输形态（如 PLATFORM→URL/FILE/BASE64），不要把 CDN 细节塞进 Agent。
- **Agent** 按 `msgType` + `MediaRef` 识别；`usableForFetch()` 且为图片时：`uploadResource` → `chat/sync` 的 `attachments[{type:IMAGE}]`。  
  需智能体绑定的 CHAT 模型开启 vision。视频 OpenAPI 暂无附件类型，仍走文本识别。
