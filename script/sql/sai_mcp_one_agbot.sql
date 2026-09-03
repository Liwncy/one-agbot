-- 把框架 MCP（one-agbot /mcp）绑到小聪明儿。
-- 与 cf-mcp-tools 并列，不要替代。
--
-- Docker Compose：SnailAI 容器访问 boot 用 http://boot:8088
-- 本机 mvn 启动：改成 http://127.0.0.1:8088
--
-- mysql -u root -p one-agbot < script/sql/sai_mcp_one_agbot.sql

INSERT INTO sai_mcp_server (
    name, description, transport_type, base_uri, endpoint, timeout
)
SELECT
    'one-agbot',
    '框架通道能力：聊天记录、随机朋友等（agbot_chat_history / agbot_chat_get / golem_random_friend）',
    2,
    'http://boot:8088',
    '/mcp',
    30000
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sai_mcp_server WHERE name = 'one-agbot'
);

UPDATE sai_mcp_server
SET description = '框架通道能力：聊天记录、随机朋友等（agbot_chat_history / agbot_chat_get / golem_random_friend）'
WHERE name = 'one-agbot';

INSERT INTO sai_agent_mcp_server (agent_id, mcp_server_id)
SELECT 1, s.id
FROM sai_mcp_server s
WHERE s.name = 'one-agbot'
  AND NOT EXISTS (
      SELECT 1 FROM sai_agent_mcp_server a
      WHERE a.agent_id = 1 AND a.mcp_server_id = s.id
  );
