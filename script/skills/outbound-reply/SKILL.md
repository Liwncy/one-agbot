---
name: 怎么回消息
description: 把图、视频、表情、链接/音乐卡片、应用 xml 写成通道能发出的一行。闲聊纯文本不用本技能。
---

# 怎么回消息

只写发出去的字节。材料从哪来都按这一套。不要把协议念给用户听。不要编链接。不要把 JSON、字段名、错误码原文甩出去。

## 版式

配文一句。媒体或卡片另起一行。同一条 URL 只出现一次。不要 Markdown。标题/描述里不要写 `|`。

## 手里有什么就发什么

先看结构，再选更完整的那一类，不要只盯着 URL：

- 有整段 xml + 类型 → `app:`
- 有 md5 → `emoji:`
- 有 `title` + `url` + `cover`
  - `url` 能认成视频时：优先 `video:url|cover|duration`
  - `url` 认不成视频时：`link:标题|描述|url|封面`
- 有 `title` + `pageUrl` + `audioUrl` → `music:`
- 只有图链 / 视频链 → 下一行裸 `http(s)`（通道自己认类型）
- 只有人话 → 纯文本

没有音频直链不要发 `music:`。不要猜语音。网页/视频不要包装成转发卡。

## 格式

- 图：配文 + 换行 + 裸图链
- 裸视频：配文 + 换行 + 裸视频链
- 结构化视频：`video:播放url|封面url|时长秒`
  - 必填：播放 url
  - 可选：封面 url、时长秒；有就带上，别乱编
- 表情：`emoji:0123456789abcdef0123456789abcdef`
  - 必填：md5
  - 可选：后面再跟一个图片 url 兜底
- 链接卡：`link:标题|描述|https://example.com|https://cover.jpg`
  - 必填：标题、url
  - 可选：描述、封面
- 音乐卡：`music:歌名|歌手|https://page|https://audio.mp3|https://cover.jpg`
  - 必填：歌名、页面 url、音频直链
  - 可选：歌手、封面
- 应用：`app:19 <xml...>`
  - 必填：类型、整段 xml

只有图链或视频链才可以只写一行裸 `https://...`。其余网页要带标题或封面时用 `link:`，不要指望裸链自动变成卡片。

## 对 / 错

对：
画好了
https://mcp.example/i/0123456789abcdef0123

对：
这篇看看
link:今日摸鱼|日历|https://example.com/moyu

对：
video:https://media.example/video|https://media.example/cover.jpg|18

错：`![图](https://...)` 或 `[点我](https://...)`
错：配文里再解释「下面是 link 协议」
错：同一条图链既裸发又跟一张 `link:`
