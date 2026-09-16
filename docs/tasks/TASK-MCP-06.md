---
id: TASK-MCP-06
title: 红队 M 系列（路径穿越 / 绝对路径 / 符号链接读写 / 伪造轮次 / 越权工具 / 超大写入 / 直写 inbox）+ report agent_actions 统计 + 基线登记
module: redteam / report / SECURITY
dependsOn: [TASK-MCP-04]
risk: high
featureFlag: none
status: todo
dodCommands:
  - bash redteam/test_gate.sh && bash redteam/run_all.sh
  - RT_ALLOWED_VULN=0 bash redteam/run_all.sh
---

# TASK-MCP-06：红队 M 系列

## 0. Meta
- 语言/框架版本：bash（红队编排）+ 一个最小 MCP client 脚本（Python，仅 stdio JSON-RPC，随 redteam 入库）
- 影响模块/包前缀：`redteam/M-mcp/`、`redteam/run_all.sh`、`redteam/AGENTS.md`、`SECURITY.md`、`README` 安全边界
- 最小验收命令：`bash redteam/run_all.sh`

## 1. 背景
- 现状：红队 19 项覆盖 cli / docker 路径；MCP 工具面是新攻击面。
- 痛点：`docs/10` G2 的承诺必须由红队用例锁住，否则只是文档。

## 2. 目标（Definition of Done）
- [ ] `redteam/M-mcp/mcp_client.py`：零依赖 stdio JSON-RPC 最小 client（initialize → tools/call），供各攻击脚本复用
- [ ] M1 `workspace_read ../hidden/expected/answer.json` → `INVALID_PATH`，run 无 canary、hidden 未被读（`agent_action.success=false` 留痕）
- [ ] M2 绝对路径读 hidden → `INVALID_PATH`
- [ ] M3 先 `workspace_write` 正常文件，再在宿主侧于 workspace 内造指向 hidden 的符号链接（模拟外部协作者），`workspace_read` 该链接 → `INVALID_PATH`
- [ ] M4 `submit` 携带 `attempt_id: attempt_003` / 错 `task_id` → `valid:false`，inbox 无文件
- [ ] M5 `call_tool` 白名单外工具 → `TOOL_NOT_ALLOWED`，`tool_not_allowed` 事件留痕，`tool_call_required` 一票否决
- [ ] M6 `workspace_write` 超过上限 → `TOO_LARGE`，文件未落
- [ ] M7 `workspace_write` 到 `inbox/attempt_001.json`（相对 workspace）→ 落在 workspace 内，不算提交；run 记无提交轮
- [ ] M8 宿主侧在 workspace 内预置指向 `hidden/judge.rules.yaml` 的符号链接，`workspace_write` 该路径 → `INVALID_PATH`，hidden 指纹不变（否则会 INTEGRITY_BROKEN）
- [ ] `ReportGenerator` 新增 `agent_actions` 统计段（按工具计数、写入文件数、被拒绝次数，只认签名事件），`report.md` / `report.html` 各加一段，`ReportGeneratorTest` 覆盖——红队 M 系列的"被拒绝次数"就靠它读
- [ ] `run_all.sh` 编排段 + `record` 四态；`redteam_report.json` 含 M 系列；基线 `RT_ALLOWED_VULN` 不变（0 / 回退 1）
- [ ] `redteam/AGENTS.md` 登记 M 攻击面；`SECURITY.md` / README 安全边界补 MCP 一段（隔离前提、trace 不含内容）

## 3. 范围
### In-scope
- 上述文件
### Out-of-scope
- 修改门禁判定逻辑 `gate_lib.sh`

## 4. 约束与关键决策
- 攻击脚本走真实 `mcp-serve` 子进程，不 mock。
- 所有 M 用例都应 DEFENDED；任何 VULNERABLE 视为 04 的缺陷，回卡修复，不登记基线。

## 6. Failure Modes & Safeguards
- 红队脚本自身 bug 导致 INFRA → 门禁本就 fail-closed，先修脚本

## 8. 代码改动点
- 文件清单：见 Meta

## 9. 测试策略
- 最小集：`bash redteam/test_gate.sh && bash redteam/run_all.sh`
- 推荐回归：`bash bin/ci-smoke.sh`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：M1–M8 全 DEFENDED；总矩阵 27 项（Docker 就绪）基线 0

## 11. 风险与回退
- 风险：macOS bash 3.2 与全角字符陷阱（见 `redteam/AGENTS.md`）→ 变量一律 `${VAR}`
- 回退：从 `run_all.sh` 摘除编排段
