---
id: TASK-MCP-05
title: 真实 MCP client 实跑：Codex（仅评测 server）跑通两任务，记录 client 兼容性
module: dogfood / docs
dependsOn: [TASK-MCP-04]
risk: medium
featureFlag: none
status: todo
dodCommands:
  - "codex exec --skip-git-repo-check --sandbox read-only -c 'mcp_servers.ael.command=\"bin/agent-eval\"' -c 'mcp_servers.ael.args=[\"mcp-serve\",\"--task\",\"tasks/api-payload-001\",\"--runs-root\",\"runs/dogfood-mcp\",\"--label\",\"codex-mcp\"]' '连接 ael 评测服务器，调用 get_task 获取任务，完成后用 submit 提交；除 ael 工具外不要使用任何其他工具。'"
  - git diff --check
---

# TASK-MCP-05：真实 client 实跑

## 0. Meta
- 语言/框架版本：Codex CLI ≥ 0.154（`mcp_servers` 配置）；有条件时 Claude Desktop / Cursor
- 影响模块/包前缀：不改代码；产物 `runs/dogfood-mcp/`（不入库）；结论写 `docs/10` 附录 A
- 最小验收命令：见 `dodCommands`（参数形式以 spike 实测为准）

## 1. 背景
- 现状：SDK client e2e 能过，不代表真实产品 client 能过（超时、并发、tool result 解析都可能不同）。
- 痛点：这是 `docs/08` R1 教训的直接复用——第一次真实接入必然暴露问题。

## 2. 目标（Definition of Done）
- [ ] Codex 仅配置评测 MCP server（`--sandbox read-only`，无写文件系统能力）跑通 `api-payload-001`（观察是否利用同步反馈修正）与 `tool-call-001`（经 `call_tool`）
- [ ] 记录：initialize 协商的 protocolVersion、tools/list 被如何呈现给模型、`submit` 长时调用是否触发 client 超时、是否出现 `pending` 路径
- [ ] `docs/10` 附录 A：结果表 + 发现与处置表（同 `docs/08` 附录 A 格式）
- [ ] 发现的框架问题各开修复 commit，引用 TASK-MCP-05

## 3. 范围
### In-scope
- 实跑、记录、必要的小修（不改合约）
### Out-of-scope
- 合约变更（回 01）；新工具

## 4. 约束与关键决策
- 只用 MCP 工具面：Codex 用 `--sandbox read-only` 剥夺文件写能力，验证"结构性隔离"叙事在真实 client 上成立。
- 模型凭证仍经环境变量；`runs/dogfood-mcp` 不入库。

## 6. Failure Modes & Safeguards
- client 对 `submit` 超时 → 走 `pending` + `get_feedback`；若 client 连 `pending` 都等不到，把阈值写进 §3.1
- Codex 忽略"只用 ael 工具"的指令去读文件 → 记录为"隔离依赖 client 配置"的证据，不算框架缺陷

## 8. 代码改动点
- 文件清单：`docs/10-MCP环境适配器设计.md`（附录 A）

## 9. 测试策略
- 最小集：两任务各至少 1 次真实 run
- 推荐回归：`--repeat 2`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：`runs/dogfood-mcp/**/report.json` 的 `run.adapter == "mcp"`；附录 A 存在

## 11. 风险与回退
- 风险：Codex 版本变化导致配置键不同 → 以 `codex --help` / 官方文档为准修正卡片命令
- 回退：删除 `runs/dogfood-mcp/`
