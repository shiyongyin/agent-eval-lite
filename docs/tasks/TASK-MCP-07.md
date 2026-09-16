---
id: TASK-MCP-07
title: 文档与接入示例：README / docs/06 / 导览 / Claude Desktop・Cursor・Codex 配置 + CHANGELOG 0.6.0
module: docs
dependsOn: [TASK-MCP-05, TASK-MCP-06]
risk: low
featureFlag: none
status: todo
dodCommands:
  - git diff --check
  - bash bin/gen-codemap.sh --check
  - rg -n "mcp-serve" README.md docs/06-小团队落地指南.md docs/05-交互式导览.html
---

# TASK-MCP-07：文档、示例与发布记录

## 0. Meta
- 语言/框架版本：Markdown / HTML
- 影响模块/包前缀：`README.md`、`docs/06`、`docs/05-交互式导览.html`、`docs/PLAYBOOK.md`（新 adapter recipe 补 mcp）、`AGENTS.md`（依赖方向 + 路由表）、`src/main/java/com/agenteval/AGENTS.md`（接线表加 mcp 包）、`evalsets/_template/`（agents.yaml 注释提 mcp 何时用）、`CHANGELOG.md`、`pom.xml` / `Version` / `Main` 0.6.0
- 最小验收命令：见 `dodCommands`

## 2. 目标（Definition of Done）
- [ ] README「Agent 接入方式」加 `mcp` 小节：适用对象、`mcp-serve` 命令、三份 client 配置示例（Claude Desktop `claude_desktop_config.json`、Cursor `.cursor/mcp.json`、Codex `config.toml [mcp_servers]`）、与 cli/http 的能力口径差异、隔离前提
- [ ] `docs/06` 接入 Agent 一节加"没有 shell 的 Agent → mcp"分支
- [ ] `docs/05` 导览：第 2 步选型表加一行、命令生成器加 mcp 模式、常见坑加"Agent 同时挂了文件系统 MCP 则隔离失效"
- [ ] `docs/PLAYBOOK.md` recipe「新 Agent 适配器」补 mcp 路径；两份 AGENTS.md 同步依赖方向与包职责
- [ ] `CHANGELOG.md` 0.6.0；版本号统一
- [ ] `docs/10` 状态改为"已实施（v1）"，v2 项保留

## 3. 范围
### In-scope
- 文档与版本号
### Out-of-scope
- 代码行为

## 9. 测试策略
- 最小集：`git diff --check`；导览页浏览器实看一次

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：三处文档命中 `mcp-serve`；CI 绿

## 11. 风险与回退
- 风险：client 配置格式随产品版本变化 → 示例注明验证日期与版本
