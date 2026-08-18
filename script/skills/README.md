# 小聪明儿 Skills（SnailAI）

系统提示词只做人设。工具怎么用、怎么发消息写在技能里，上传到 SnailAI「技能管理」后绑到智能体。

建议绑三个技能：

- `mcp-tools/SKILL.md`：何时调哪个 MCP 工具
- `outbound-reply/SKILL.md`：图/视频裸链、表情、链接/音乐卡、应用 xml 怎么写成通道能发的一行
- `roleplay-modes/SKILL.md`：切人设 `mode_set`，之后按上次返回的 instruction 演；维护仅前缀 `owner`

MCP 加了新工具：在 `mcp-tools/SKILL.md` 的表里补一行，不要写回 `sai_agent.instruction`。

人设脚本：`script/sql/update_agent_xiaocongminger_prompt.sql`。
