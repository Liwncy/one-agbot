# 小聪明儿 Skills（SnailAI）

系统提示词只做人设。工具怎么用、怎么发消息写在技能里，上传到 SnailAI「技能管理」后绑到智能体。

建议绑四个技能：

- `mcp-tools/SKILL.md`：何时调哪个通用 MCP 工具；闲聊不调；对方要诗走诗词
- `xiuxian-adventure/SKILL.md`：修仙剧情探索的状态恢复、选项推进、原样回复与真实结算；短数字和追问也由它承接
- `wechat-play/SKILL.md`：绑在这台微信登录态上的玩法（随机朋友、聊天记录卡等）；不写进 mcp-tools
- `outbound-reply/SKILL.md`：按意图选类型，按定长槽位写出站行；缺必填槽降级，不编链接

通用 MCP 加了新工具：在 `mcp-tools/SKILL.md` 的表里补一行。修仙剧情规则集中写进
`xiuxian-adventure/SKILL.md`，`mcp-tools` 只保留转交说明；Golem 专属工具写进
`wechat-play/SKILL.md`。都不要写回 `sai_agent.instruction`。

框架通道工具在 boot `/mcp`（聊天记录、微信登录态玩法等），和 cf-mcp-tools 不是同一个 MCP。具体工具名看各技能正文。

人设脚本：`script/sql/update_agent_xiaocongminger_prompt.sql`。
角色目录：`script/sql/agbot_roleplay_character.sql`（会话绑定仍在 Redis）。
