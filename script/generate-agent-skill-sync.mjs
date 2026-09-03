import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const agentId = 1;
const skillPaths = [
  "skills/mcp-tools/SKILL.md",
  "skills/outbound-reply/SKILL.md",
  "skills/wechat-play/SKILL.md",
];

function parseSkill(relativePath) {
  const raw = readFileSync(join(__dirname, relativePath), "utf8").replaceAll(
    "\r\n",
    "\n",
  );
  const match = raw.match(/^---\n([\s\S]*?)\n---\n?([\s\S]*)$/u);
  if (!match) throw new Error(`${relativePath}: frontmatter 格式不正确`);

  const metadata = {};
  for (const line of match[1].split("\n")) {
    const separator = line.indexOf(":");
    if (separator < 1) continue;
    metadata[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  }
  if (!metadata.name || !metadata.description) {
    throw new Error(`${relativePath}: 缺少 name 或 description`);
  }
  return {
    name: metadata.name,
    description: metadata.description,
    content: match[2].replace(/\s+$/u, "") + "\n",
  };
}

function sqlString(value) {
  return `'${value.replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}

const skills = skillPaths.map(parseSkill);
const statements = skills.flatMap((skill) => {
  const name = sqlString(skill.name);
  const description = sqlString(skill.description);
  const content = sqlString(skill.content);
  return [
    `INSERT INTO sai_skill (name, description, skill_content, version, has_files, create_dt, update_dt)
SELECT ${name}, ${description}, ${content}, 0, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sai_skill WHERE name = ${name});`,
    `UPDATE sai_skill
SET description = ${description},
    skill_content = ${content},
    version = COALESCE(version, 0) + 1,
    update_dt = NOW()
WHERE name = ${name};`,
    `INSERT INTO sai_agent_skill (agent_id, skill_id)
SELECT ${agentId}, s.id
FROM sai_skill AS s
WHERE s.name = ${name}
  AND NOT EXISTS (
    SELECT 1 FROM sai_agent_skill AS a
    WHERE a.agent_id = ${agentId} AND a.skill_id = s.id
  );`,
  ];
});
const names = skills.map((skill) => sqlString(skill.name)).join(", ");
const sql = `-- 由 script/generate-agent-skill-sync.mjs 生成，请勿手工修改
-- 同步 SKILL.md 的 description 和正文；不存在则插入并绑到智能体；version 递增刷新缓存
START TRANSACTION;

${statements.join("\n\n")}

COMMIT;

SELECT s.id, s.name, s.version, s.update_dt
FROM sai_skill AS s
JOIN sai_agent_skill AS a ON a.skill_id = s.id
WHERE a.agent_id = ${agentId}
  AND s.name IN (${names})
ORDER BY s.id;
`;

const output = join(
  __dirname,
  "sql/update_agent_xiaocongminger_skills.sql",
);
writeFileSync(output, sql, "utf8");
console.log(`wrote ${skills.length} skills -> ${output}`);
