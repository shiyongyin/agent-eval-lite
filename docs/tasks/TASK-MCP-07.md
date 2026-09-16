---
id: TASK-MCP-07
title: 文档与接入示例（README / docs/06 / 导览 / PLAYBOOK / AGENTS）+ CHANGELOG 0.6.0 + 版本号
module: docs
dependsOn: [TASK-MCP-05, TASK-MCP-06]
risk: low
featureFlag: none
status: todo
dodCommands:
  - git diff --check
  - bash bin/gen-codemap.sh --check
  - rg -n "mcp-serve" README.md docs/06-小团队落地指南.md docs/05-交互式导览.html docs/PLAYBOOK.md
  - "rg -n '0\\.6\\.0' pom.xml src/main/java/com/agenteval/Version.java src/main/java/com/agenteval/cli/Main.java CHANGELOG.md"
  - mvn -q verify
---

# TASK-MCP-07：文档、示例与发布记录

## 0. Meta
- 语言/框架版本：Markdown / HTML / TOML / JSON 示例
- 影响模块/包前缀：`README.md`、`docs/06-小团队落地指南.md`、`docs/05-交互式导览.html`、`docs/PLAYBOOK.md`、`AGENTS.md`、`src/main/java/com/agenteval/AGENTS.md`、`evalsets/_template/agents.yaml` + `src/main/resources/evalset-template/agents.yaml`（注释）、`CHANGELOG.md`、`pom.xml` / `Version.java` / `Main.java`（0.6.0）、`docs/10`（状态）
- 最小验收命令：见 `dodCommands`

## 2. 目标（Definition of Done）
- [ ] DoD-1：README「Agent 接入方式」新增 `### mcp` 小节，内容依次：适用对象（没有 shell / 不是常驻服务、但能配 MCP server 的 Agent）；一句话原理（Agent 连框架，工具面 7 个）；`mcp-serve` 命令与选项；三份 client 配置示例（下方 §5）；与 cli/http 的口径差异（评的是"只用受控工具面"，`run.adapter` 区分，不混比）；隔离前提；`--sandbox docker` 何时仍需要
- [ ] DoD-2：`docs/06`「接入 Agent」加分支"没有 shell 的 Agent → mcp"，命令与 README 一致；「看报告」加 `agent_actions` 一行
- [ ] DoD-3：`docs/05` 导览：第 2 步选型表加一行 `--agent mcp（mcp-serve）`；对接全景表提交列注明 MCP 用 `submit` 工具；命令生成器加 `mcp` 模式（输出 `mcp-serve` 命令 + Codex 配置片段）；常见坑加"Agent 同时挂了文件系统 / shell 工具则 MCP 隔离失效"；时间线加 0.6.0 条目；版本号 0.6.0
- [ ] DoD-4：`docs/PLAYBOOK.md` recipe「新 Agent 适配器」补 mcp 一节（钩子 / 通道 / 传输三层）；`AGENTS.md` 依赖方向加 `mcp`；`src/main/java/com/agenteval/AGENTS.md` 接线表加"新 MCP 工具：`EnvironmentToolSpecs` → 快照 → `EnvironmentTools` handler → `agent_action` 留痕 → 红队 M"
- [ ] DoD-5：`evalsets/_template/agents.yaml` 与资源副本加注释："被测 Agent 只有 MCP client 时不走 agents.yaml，用 `mcp-serve` 单任务接入（v1 不支持 suite）"；`EvalsetInitScaffoldTest` 快照一致
- [ ] DoD-6：`CHANGELOG.md` 0.6.0（新增 / 修复 / 文档三段，含 TASK-MCP-05 发现修复条目）；`pom.xml`、`Version.ENGINE`、`Main` version 统一 0.6.0
- [ ] DoD-7：`docs/10` 头部状态改"已实施（v1，0.6.0）"，§2.6 决策段、附录 A 实跑记录齐全；`docs/11` 状态列回写
- [ ] DoD-8：导览页浏览器实看一次（本地 `python3 -m http.server`），mcp 模式命令生成器输出正确

## 3. 范围
### In-scope
- 文档与版本号；不改行为
### Out-of-scope
- 代码逻辑；v2 卡片内容

## 5. 合约（client 配置示例，以 05 实测为准修正）

Claude Desktop `claude_desktop_config.json`：
```json
{"mcpServers":{"ael":{"command":"/abs/path/agent-eval-lite/bin/agent-eval","args":["mcp-serve","--task","/abs/path/tasks/api-payload-001","--runs-root","/abs/path/runs","--label","claude-desktop"]}}}
```
Cursor `.cursor/mcp.json`：
```json
{"mcpServers":{"ael":{"command":"/abs/path/agent-eval-lite/bin/agent-eval","args":["mcp-serve","--task","/abs/path/tasks/api-payload-001","--label","cursor-agent"]}}}
```
Codex `config.toml`：
```toml
[mcp_servers.ael]
command = "/abs/path/agent-eval-lite/bin/agent-eval"
args = ["mcp-serve", "--task", "/abs/path/tasks/api-payload-001", "--label", "codex-mcp"]
```
每份示例后注明验证日期与 client 版本。

## 9. 测试策略
- 最小集：`git diff --check`；`mvn -q verify`（版本号改动影响 `Version` 引用的测试）
- 推荐回归：`bash bin/ci-smoke.sh`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：四处文档命中 `mcp-serve`；四处版本号 0.6.0；CI 绿

## 11. 风险与回退
- 风险：client 配置格式随版本变化 → 示例注明验证日期；不写不确定的键
- 回退：文档改动可整体 revert
