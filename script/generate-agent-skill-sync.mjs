import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const agentId = 1;
const skillPaths = [
  "skills/mcp-tools/SKILL.md",
  "skills/outbound-reply/SKILL.md",
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
const statements = skills.map(
  (skill) => `UPDATE sai_skill AS s
JOIN sai_agent_skill AS a ON a.skill_id = s.id
SET s.description = ${sqlString(skill.description)},
    s.skill_content = ${sqlString(skill.content)},
    s.version = COALESCE(s.version, 0) + 1,
    s.update_dt = NOW()
WHERE a.agent_id = ${agentId}
  AND s.name = ${sqlString(skill.name)};`,
);
const names = skills.map((skill) => sqlString(skill.name)).join(", ");
const sql = `-- 由 script/generate-agent-skill-sync.mjs 生成，请勿手工修改
-- 同步 SKILL.md 的 description 和正文；version 递增用于刷新运行时缓存
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
