---
id: TASK-MCP-05
title: 真实 MCP client 实跑：Codex 仅挂评测 server 跑通 api-payload-001 与 tool-call-001，记录兼容性与问题
module: dogfood / docs
dependsOn: [TASK-MCP-04]
risk: medium
featureFlag: none
status: todo
dodCommands:
  - "test -f /tmp/codex-mcp/config.toml"
  - "CODEX_HOME=/tmp/codex-mcp codex exec --skip-git-repo-check --sandbox read-only '连接 ael 评测服务器：先调用 get_task 获取任务与题面，按题面完成后用 submit 提交；若返回未通过且有 next_attempt_id，按反馈修正后再次 submit。除 ael 服务器提供的工具外不要使用任何其他工具，也不要读写本机文件。'"
  - "ls runs/dogfood-mcp/api-payload-001/run_*/report/report.json"
  - git diff --check
---

# TASK-MCP-05：真实 client 实跑

## 0. Meta
- 语言/框架版本：Codex CLI ≥ 0.154（本机已装；`mcp_servers` 配置在 `$CODEX_HOME/config.toml`）；有条件时 Claude Desktop（`claude_desktop_config.json`）、Cursor（`.cursor/mcp.json`）
- 影响模块/包前缀：不改主干代码；产物 `runs/dogfood-mcp/`（`.gitignore` 已覆盖 `runs/`）；结论写 `docs/10` 附录 A；发现的框架问题各自单独 commit 修复
- 最小验收命令：见 `dodCommands`

## 1. 背景
- 现状：SDK client e2e 全绿 ≠ 真实产品 client 能过（`docs/08` R1 教训：第一次真实接入必然暴露问题）。
- 痛点：MCP 模式的"结构性隔离"叙事必须在一个**没有文件系统能力**的真实 client 上成立一次。

## 2. 目标（Definition of Done）
- [ ] DoD-1：准备独立 `CODEX_HOME=/tmp/codex-mcp`（不动用户 `~/.codex`），`config.toml` 内容：复制用户 provider 段 + 下述 server 段（jar 与任务路径用绝对路径）：
  ```toml
  [mcp_servers.ael]
  command = "/Users/mac/work/agent-eval-lite/bin/agent-eval"
  args = ["mcp-serve", "--task", "/Users/mac/work/agent-eval-lite/tasks/api-payload-001", "--runs-root", "/Users/mac/work/agent-eval-lite/runs/dogfood-mcp", "--label", "codex-mcp", "--model", "gpt-5.6-sol"]
  ```
- [ ] DoD-2：`api-payload-001`：Codex `--sandbox read-only` 下跑通；记录 `report.json` 的 `status / score_trajectory / attempts[].valid`，以及 Codex 是否利用了 `submit` 返回的反馈（第二轮分数是否上升）
- [ ] DoD-3：`tool-call-001`（改 `args` 中任务路径）：经 `call_tool` 完成两次真实调用并 PASSED；`tool_usage.total_calls==2`
- [ ] DoD-4：兼容性记录：Codex 协商的 `protocolVersion`、`tools/list` 描述被模型如何引用、`submit` 是否触发 `pending` 路径（stderr 日志里有耗时）、有无 `notifications/cancelled`、Codex 是否尝试读本机文件（应被 read-only 沙箱与 prompt 双重阻止；若发生记为"隔离依赖 client 配置"证据）
- [ ] DoD-5：`docs/10` 附录 A（格式同 `docs/08` 附录 A）：A.1 环境、A.2 结果表、A.3 发现与处置表、A.4 对 v1 剩余卡片（06/07）与 v2 的影响
- [ ] DoD-6：框架问题逐个修复并单独 commit，commit message 以 `TASK-MCP-05 发现修复:` 开头；合约不改（若必须改 → 回 01 走快照流程并在附录说明）

## 3. 范围
### In-scope
- 实跑、记录、不改合约的小修
### Out-of-scope
- 新工具、传输变更、任务 hidden 规则

## 4. 约束与关键决策
- 隔离叙事验证：Codex 必须 `--sandbox read-only`；prompt 明确"只用 ael 工具"。
- 每个任务至少 1 次；预算允许时 `api-payload-001` 跑 2 次看稳定性。
- 凭证经环境变量 / `CODEX_HOME` 的 config，不入库；`/tmp/codex-mcp` 不入库。

## 6. Failure Modes & Safeguards
- Codex 加载 server 失败 → 先 `bin/agent-eval mcp-serve --task … < /dev/null 2>/tmp/e.err` 单独验证进程可起，再查 `$CODEX_HOME/log`
- `submit` 超时 → 走 `pending`+`get_feedback`；若 client 连 pending 帧都没等到，记录并回 00 的阈值
- Codex 不调 `get_task` 直接猜 → 记录为 prompt 问题，调整 DoD-2 的指令再跑一次

## 8. 代码改动点
- 文件清单：`docs/10-MCP环境适配器设计.md`（附录 A）；可能的修复 commit

## 9. 测试策略
- 最小集：两任务各 1 次真实 run
- 推荐回归：`api-payload-001` × 2

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：`runs/dogfood-mcp/**/report.json` 存在且 `run.adapter=="mcp"`；附录 A 完整

## 11. 风险与回退
- 风险：Codex 配置键随版本变化 → 以 `codex --help` 与官方文档为准修正 DoD-1
- 回退：`rm -rf runs/dogfood-mcp /tmp/codex-mcp`
