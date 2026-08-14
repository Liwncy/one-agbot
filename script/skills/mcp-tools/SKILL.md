---
name: MCP 工具用法
description: 小聪明儿调用 cf-mcp-tools 的分流、参数与回法。闲聊不调工具。
---

# MCP 工具用法

仍是一份技能，按领域写细。系统提示词只做人设，这里管「何时调、怎么填、怎么回」。

## 通用

用户正文常带前缀：`[userId/昵称 scope=group:...] 原话`（私聊 `scope=user:wxid`；主人会多一个单词 `owner`）。有前缀时身份和 scope 都从这一条抄；没有就不要猜。调人设工具时 scope 原样用，不要自行加减 `@chatroom`。

- 身份用括号里的 `userId`（平台账号 id），昵称只给人看、可作 `userName` / `senderName`
- 维护类工具（人设 upsert/delete 等）只认前缀里的 `owner`，不要看昵称
- `content` / `text` / `prompt` 只用**原话**，不要把 `[userId/昵称]` 整段塞进工具
- 没拿到成功结果前，不要说已经做好了，也不要编细节
- 禁止编造、拼接、猜测任何媒体链接；禁止拿历史旧链接顶替本次结果
- 工具返回的 JSON、字段名、错误码、堆栈不要原文甩给用户，只说人话
- 回图/视频：一句配文 + 下一行裸链接，不要 Markdown
- 闲聊、接话、问好：不调工具
- 人设切换看「人设模式」技能，不在这里切
- `echo`、`get_current_time`、`random_cat_image`：对方明确要才用

## 怎么选

| 对方想要 | 走下面哪一节 |
|---|---|
| 画一张 / 生成图 | 文生图 |
| 分类图、随机图、今日老婆 | 现成图 |
| 表情、梗图、反应图 | 表情图库 |
| 做视频、查进度、分类视频、短链解析、识图 | 视频与识图 |
| 天气、摸鱼、口令、目录小能力 | 规则能力 |
| 切人设 / 当前人设 | 人设模式技能：`mode_get` / `mode_set`；维护仅前缀带 `owner` |
| 修仙 / 八字星座玄学 | 修仙与玄学 |
| 吃不准且像口令 | 先 `rule_execute` |
| 吃不准且像闲聊 | 直接说话 |

不要两个画图工具一起调；专用工具能覆盖时不要先 `rule_list`。

## 文生图

- 普通「画一只猫 / 生成一张…」→ `draw_image`
  - `prompt`=场景描述
  - `scale` 可选：`1:1` / `3:4` / `4:3` / `16:9` / `9:16`，默认 `4:3`
- 对方明确说快速、草图、封面、随便画一下 → `draw_image_fast`（默认 `1:1`）
- 不要两个都调
- 失败就说没弄成

## 现成图

- 分类图（黑丝/原神/风景等）→ `fetch_haokan_image`
  - `query`=分类词或原话里的取图要求
  - 不知道有哪些分类：`listCategories=true`
- 因果随机图 → `fetch_yinguo_image`
  - 默认鉴黄，风险图会转线稿再给短链
  - 只有对方明确要原图才 `allowRaw=true`
- 今日老婆 → `today_wife`，`userId`=发言人 wxid（同一人当天结果稳定）
- 只要猫图且对方点名要猫 → `random_cat_image`

## 表情图库

想发反应图、梗图、表情：先 `emoji_search`（中文搜 description/tags，如 无奈、猫）。不要编 md5，不要让用户背名字。

发出去：

- 有 `md5`：单独一行 `emoji:<md5>`，同一行可再跟 `imageUrl`（能发明文表情的通道用 md5）
- 没有 md5：一句配文 + 下一行 `imageUrl` 当普通图

写库：

- 对方明确说「存下来」才 `emoji_save`（至少 `md5` 或 `imageUrl`）
- 说名字/描述/标签不对：先 search 或 `emoji_get`（按 md5 或 name），再 `emoji_update`（只改他说的字段：newName / description / tags / category / status）
- 标签只用中文词
- 不要自动乱存

## 视频与识图

生成视频：

1. `submit_agnes_video`：`prompt`=画面/动作；图生视频再带公网 `imageUrl`
2. 拿到 `videoId` 后用 `query_agnes_video` 查到 `status=completed` 且有 `videoUrl` 再发
3. 未完成可以说稍等，不要编视频地址

现成视频：

- 分类视频（小姐姐/热舞等）→ `fetch_haokan_video`（可 `listCategories=true`）
- 抖音/快手/小红书/B 站等分享口令或链接 → `parse_short_video`，`text`=对方整段分享原文

识图：

- 「这图是什么 / 帮我认一下」且有公网图链 → `recognize_image`（只要 `imageUrl`）
- 微信里刚发的图若已作为附件给模型，先看附件；没有公网 URL 再说明认不了

## 规则能力

天气、摸鱼、以及目录里「一句话触发」的能力：直接 `rule_execute`。不要等对方说「规则引擎」。

参数：

- `content`=用户原话（不要带 `[userId/昵称]`）
- 有身份就带 `context.from`=wxid、`context.senderName`=昵称

结果：

- `matched=true`：按 `kind` 用人话转发 `value`（text 直说；image/voice 贴链接；app/link 把 value/meta 交给宿主，不要改写成假内容）
- `matched=false` 或失败：自己聊，或改用画图/玩法等专用工具；不要编造

`rule_list` 只在这两种情况用：对方问「你还能干啥 / 有哪些口令」，或 execute 连续 miss 想确认目录里有没有。可带 `query` 搜 description。普通人不要改规则库，`includeInactive` 默认不要开。

## 修仙与玄学

修仙（打坐、境界、改名等）→ `xiuxian_action`：

- `platform`=`agbot`（必填）
- `userId`=发言人 wxid（角色绑定键；不要念给用户听，也不要用 wxid 当道号）
- `userName`=昵称（不要填成 wxid）；没角色时用它当默认道号
- `text`=修仙相关原话（如「修仙状态」「修仙改名 青云子」）
- 只要目录可 `listHelp=true`

玄学（八字、星座、运势）→ `xuanxue_query`：

- `text`=完整口令（如「八字测算 张三 1990-01-01 12:00 男」）
- 只想看有哪些指令：`listHelp=true`
- 缺生日等关键信息就先问一句，别瞎填
