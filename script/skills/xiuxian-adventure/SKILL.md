---
name: 修仙剧情探索
description: 进入修仙世界、修仙探索、继续探索时使用；上一条助手消息带【修仙探索 x/y】时，用户只回复 1/2/3/4、中文序号（一二三四或第一项等）、明确选项文字、问号或追问也必须使用本技能。短数字回复优先命中本技能并恢复修仙剧情，不要当普通闲聊。
version: 1.0.0
---

# 修仙剧情探索

本技能只负责 `xiuxian_adventure` 的分步剧情。普通修仙操作和明确的「修仙速探 /
快速探索 / 原来的探索」仍走 `xiuxian_action`，不得互相替代。

## 识别当前消息

用户正文可能带 `[userId/昵称 scope=…]` 前缀和近期上下文。身份取本条消息方括号
中的完整 id 与昵称；意图只取 `[本条]` 后的原话。上一条助手消息出现
`【修仙探索 x/y】` 时，即使本条只有 `1`、`二`、`第四个`、选项原文、`？`、
「这个会怎样」等短回复，也必须回到本技能。

- 有效选择：当前公开选项范围内的阿拉伯数字、中文序号，或能唯一对应当前选项的
  明确文字。
- 非选择：`?` / `？`、问句、追问、评价、闲聊、玩法讨论、询问命令，以及不能唯一
  对应选项的文字。不得猜选项。

## 固定调用参数

每次调用都带 `platform=agbot`、完整 `userId`、`userName` 和 `requestId`。
每个新动作生成新的 `requestId`；只有同一次调用重试才复用。工具返回的 `version`
原样用于紧接着的下一次状态动作，不得猜测或沿用旧版本。

## 每轮流程

1. 每轮先调用 `action=status` 恢复服务端状态，再决定是否改变状态。不得只凭聊天
   记忆选择、续写或结算。
2. 用户在讨论玩法、询问命令时，只解释，不执行 `start`、`choose`、`continue` 或
   `finalize` 等任何改变状态的动作。
3. 当前有未完成探索时：
   - 只有有效编号或明确选项文字才调用 `action=choose`。
   - 问号、普通问题、追问、模糊文字都不得 `choose`；本轮只把 `status` 返回的
     `replyText` 原样重发，不回答隐藏后果，不替用户做决定。
4. 当前没有未完成探索时，只有用户明确说进入修仙世界、开始/继续修仙探索，才
   `action=start`。先生成全中文场景和 2～4 个简短、差异明确的中文选项，提交
   `scene` 与 `options`。用户只是在问玩法或命令时不得开始。
5. `choose` 若尚未结束并返回 `phase=awaiting_scene`，只根据
   `chosen.outcome.cue` 生成全中文的「本次选择后果 + 下一幕 + 2～4 个编号选项」，
   再调用 `action=continue` 保存。不得夹带英文叙事，不得泄露 cue、version、
   requestId、内部 JSON、奖励档位或用户 id。
6. 如果本轮 `status` 已是 `phase=awaiting_scene`，说明上次在 `choose` 后中断；
   同样根据 `status.chosen.outcome.cue` 生成下一步并调用 `continue`，不得另做一次
   `choose`。若是 `phase=awaiting_exclusive`，直接按下文规则调用 `finalize`。

## 对外回复

- `start`、`continue`、`status` 以及任何 `status=completed` 结果的 `replyText`
  是唯一可信的公开文案。凡本轮需要发送这些结果时，必须逐字原样发送，保留标题、
  换行和全部编号选项；不得改写、删减、补充，也不得另加「选哪个」。
- 问号、追问或无效选择只原样重发本轮 `status.replyText`。
- 未拿到 `status=completed` 前，禁止写「探索结算」，禁止声称获得任何物品、
  属性、灵石、修为或奖励。
- 拿到 `status=completed` 后只发送该次工具真实 `replyText`，不得复述、总结、
  润色或添加引导。`replyText` 已包含一次「还要继续探索吗」，不得再问第二遍；
  之后必须等用户明确说继续探索才重新 `start`，不得引导「速探」。
- 冷却、过期、冲突和失败也只依据工具公开 `replyText` 处理；冲突先重新
  `status`，不得重复选择或结算。

## 独珍 finalize

`choose` 返回 `phase=awaiting_exclusive` 或 `needsExclusiveItem=true` 时，根据
本段中文剧情生成独珍，并调用 `action=finalize`。AI 只允许提交：

- `name`：中文名称
- `slot`：工具允许的装备部位
- `flavorText`：全中文来历描述

禁止提交或编造品质、攻击、防御、气血、闪避、暴击、评分、等级、套装、奖励数量
及任何其他字段。`finalize` 完成后只原样发送其 `completed.replyText`。
