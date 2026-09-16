---
id: TASK-MCP-09
title: v2：suite 会话（next_task）与 `suite --agent mcp`
module: mcp / runner / cli
dependsOn: [TASK-MCP-07]
risk: medium
featureFlag: none
status: deferred
dodCommands:
  - mvn -q -Dtest='SuiteRunnerTest' test
---

# TASK-MCP-09：多任务会话（v2）

## 1. 背景
- v1 一个 server 进程 = 一次 run；批跑要 Agent 逐个连，自动化 Agent 平台需要"一次连接跑完整个 suite"。

## 2. 目标（Definition of Done）
- [ ] 工具面新增 `next_task`：当前 run 结束后返回下一任务的 `get_task` 快照；无任务返回 `suite_finished`
- [ ] `suite --agent mcp --label <l>`：按任务顺序为每个任务开一次 run，复用同一会话；`repeat` 语义不变
- [ ] `suite_report` / `history` 对 mcp adapter 无特殊处理（`run.adapter == "mcp"` 已足够）

## 4. 约束与关键决策
- 合约变更（新增工具）走 TASK-MCP-01 的快照更新流程。
- 不做任务间状态共享；每个 run 仍是独立 workspace。
