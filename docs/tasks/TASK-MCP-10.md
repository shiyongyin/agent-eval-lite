---
id: TASK-MCP-10
title: v2：操作者工具面（run / suite / report / history 给 AI 助手用），与被测者工具面物理分离
module: mcp / cli
dependsOn: [TASK-MCP-08]
risk: medium
featureFlag: none
status: deferred
dodCommands:
  - mvn -q -Dtest='McpOperatorServerTest' test
---

# TASK-MCP-10：操作者 MCP server（v2）

## 1. 背景
- 没有 shell 的 AI 助手（Claude Desktop 等）想替人跑评测、读报告；现有 skills 只覆盖有 shell 的编码 Agent。

## 2. 目标（Definition of Done）
- [ ] `agent-eval mcp-operate`：独立进程、独立工具面（`list_tasks` / `validate_task` / `run_eval` / `run_suite` / `get_report` / `get_history`），只读 `report.json` 等公开产物，绝不暴露 `judge/` 与 `hidden/`
- [ ] 与 `mcp-serve`（被测者）不共享进程、不共享工具列表，杜绝"被测 Agent 给自己判分"
- [ ] `ael-analyze-results` skill 增加"经 MCP 操作者工具读取"的分支

## 4. 约束与关键决策
- 操作者工具的输出即 `ael-analyze-results` 的输入，字段与 CLI 产物一致，不新增报告字段。

## 11. 风险与回退
- 风险：两套工具面混用 → 命令名、进程、文档三处都强调分离；红队补一条"被测者会话里调用操作者工具应不存在"
