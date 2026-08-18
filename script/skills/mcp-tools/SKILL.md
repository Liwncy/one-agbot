---
name: MCP 工具用法
description: 小聪明儿调用 cf-mcp-tools 的分流、参数与回法。闲聊不调工具。
---

# MCP 工具用法

仍是一份技能，按领域写细。系统提示词只做人设，这里管「何时调、怎么填」。发出去看「怎么回消息」。

## 通用

用户正文常带前缀：`[userId/昵称 scope=group:...] 原话`（私聊 `scope=user:wxid`；主人会多一个单词 `owner`）。有前缀时身份和 scope 都从这一条抄；没有就不要猜。调人设工具时 scope 原样用，不要自行加减 `@chatroom`。

- 身份用括号里**斜杠前**那一整段 id（常以 `wxid_` 开头）。必须完整复制，不要去掉 `wxid_`，不要截断，不要改成昵称或斜杠后的字
- 维护类工具（人设 upsert/delete 等）只认前缀里的 `owner`，不要看昵称
- `content` / `text` / `prompt` 只用**原话或转好的指定指令**，不要把 `[userId/昵称 scope=…]` 这整段 Golem 前缀塞进工具
- 没拿到成功结果前，不要说已经做好了，也不要编细节
- 禁止编造、拼接、猜测任何媒体链接；禁止拿历史旧链接顶替本次结果
- 工具返回的 JSON、字段名、错误码、堆栈不要原文甩给用户，只说人话
- 工具给了图/视频/表情/卡片结果：按「怎么回消息」发出去，不要在这里发明格式
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
| 浇水、收菜、除虫、种地、院子、主院 | 庄园 |
| 吃不准且像口令 | 先 `rule_execute` |
| 吃不准且像闲聊 | 直接说话 |

不要两个画图工具一起调；专用工具能覆盖时不要先 `rule_list`。

## 文生图

- 普通「画一只猫 / 生成一张…」→ `draw_image`
  - `prompt`=场景描述
  - `scale` 可选：`1:1` / `3:4` / `4:3` / `16:9` / `9:16`，默认 `4:3`
- 对方要画自己的院子：先 `manor_action` `text=庄园描绘`，把返回描写当 `prompt`，不要改写。只看主院、升级、一览时不要自己出图
- 对方要画自己的修士模样：先 `xiuxian_action` `text=修仙描绘`，把返回描写当 `prompt`，不要改写。只看境界、背包时不要自己出图
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
- 今日老婆 → `today_wife`，`userId`=斜杠前完整 wxid（同一人当天结果稳定）
- 只要猫图且对方点名要猫 → `random_cat_image`

## 表情图库

想发反应图、梗图、表情：先 `emoji_search`（中文搜 description/tags，如 无奈、猫）。不要编 md5，不要让用户背名字。发出去按「怎么回消息」。

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
  - 成功后优先按结构发，不要只贴裸 URL
  - 有 `title` + `url` + `cover` 时：
    - `url` 能认成视频 → `video:url|cover|duration`
    - `url` 认不成视频 → `link:标题|平台视频|url|cover`
  - `duration` 没给就留空，不要编
  - 只有对方明确要原始直链，才只回 URL

识图：

- 「这图是什么 / 帮我认一下」且有公网图链 → `recognize_image`（只要 `imageUrl`）
- 微信里刚发的图若已作为附件给模型，先看附件；没有公网 URL 再说明认不了

## 规则能力

天气、摸鱼、以及目录里「一句话触发」的能力：直接 `rule_execute`。不要等对方说「规则引擎」。

参数：

- `content`=用户原话（不要带 `[userId/昵称]`）
- 有身份就带 `context.from`=wxid、`context.senderName`=昵称

结果：

- `matched=true`：按 `kind` 把 `value` 交给「怎么回消息」发出去。text 直说；image/video 优先按结果里更完整的结构发，没有结构再当裸链；link/app 原样贴；voice 只有音频 URL 时当链接卡，不要假装是语音。不要改写成假内容、不要念 JSON
- `matched=false` 或失败：自己聊，或改用画图/玩法等专用工具；不要编造

`rule_list` 只在这两种情况用：对方问「你还能干啥 / 有哪些口令」，或 execute 连续 miss 想确认目录里有没有。可带 `query` 搜 description。普通人不要改规则库，`includeInactive` 默认不要开。

## 修仙与玄学

修仙（打坐、境界、改名等）→ `xiuxian_action`：

- `platform`=`agbot`（必填）
- `userId`=斜杠前完整 wxid（含 `wxid_`，不要截断、不要当道号念出来）
- `userName`=昵称（不要填成 wxid）
- `text`=按意图转好的修仙指令（打坐 → `修仙修炼`，看境界 → `修仙状态`）。不要带 `[userId/昵称]` 那整段
- 对方要画自己 / 看自己长啥样：`text`=`修仙描绘`，返回描写原样给用户或当 `draw_image` 的 prompt，不要润色、不要补灵石等级
- 只要目录可 `listHelp=true`

玄学（八字、星座、运势）→ `xuanxue_query`：

- `text`=完整口令（如「八字测算 张三 1990-01-01 12:00 男」）
- 只想看有哪些指令：`listHelp=true`
- 缺生日等关键信息就先问一句，别瞎填

## 庄园

种地、浇水、收菜、除虫、喂养、钓鱼、主院、商店 → `manor_action`（不要走修仙，不要说不会）。

- `platform`=`agbot`（必填）
- `userId`=斜杠前完整 wxid（含 `wxid_`，不要截断、不要当院名）
- `userName`=昵称（不要填成 wxid）
- `text` 必须以「庄园」开头，口令以帮助返回为准，不要自创、不要把口语或 Golem 前缀塞进去
- 先按意图查帮助，再用帮助里的口令去执行，不必再问用户。关键词只能用下面五个：

| 对方在说 | 先调 |
|---|---|
| 怎么玩 / 总览 / 对不上分区 | `庄园帮助` |
| 种地、浇水、施肥、捉虫、收菜 | `庄园帮助 农场` |
| 养鸡、喂养、换畜、牧场 | `庄园帮助 牧场` |
| 鱼塘、投苗、捞塘 | `庄园帮助 渔场` |
| 门楼、正房、院子长啥样、升级建筑 | `庄园帮助 主院` |
| 钓鱼 | `庄园帮助 钓鱼` |

- 本轮帮助已经给过的口令可以直接再调。对方明确要画院子：先 `庄园描绘`，返回描写原样当 `draw_image` 的 prompt，不要润色、不要补金币等级。闲聊不调。
