---
id: TASK-MCP-06
title: 红队 M1–M9（MCP 工具面攻击）+ report `agent_actions` 统计段 + SECURITY/README 边界 + 基线登记
module: redteam / report / SECURITY
dependsOn: [TASK-MCP-04]
risk: high
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='ReportGeneratorTest' test
  - bash redteam/test_gate.sh && bash redteam/run_all.sh
  - RT_ALLOWED_VULN=0 bash redteam/run_all.sh
  - "python3 -c \"import json;d=json.load(open('runs/redteam/redteam_report.json'));ms=[r for r in d['results'] if r['name'].startswith('M')];assert len(ms)==9 and all(r['verdict']=='DEFENDED' for r in ms),ms;print('M1-M8 DEFENDED')\""
---

# TASK-MCP-06：红队 M 系列 + 报告统计 + 边界文档

## 0. Meta
- 语言/框架版本：bash 3.2 兼容（macOS；变量一律 `${VAR}`，见 `redteam/AGENTS.md`）+ Python 3 标准库（最小 MCP client）；Java 17（报告统计）
- 影响模块/包前缀：新 `redteam/M-mcp/`（`mcp_client.py` + 8 个攻击脚本）、`redteam/run_all.sh`（新增 M 编排段）、`redteam/AGENTS.md`（登记攻击面）、`report/ReportGenerator.java`（+`agent_actions`）、`src/main/resources/report/page.html`（+一段）、`SECURITY.md`、`README.md`「安全边界」
- 最小验收命令：`bash redteam/run_all.sh`

## 1. 背景
- 现状：19 项红队覆盖 cli/docker；MCP 工具面是全新攻击面；`docs/10` G2 承诺"结构性接触不到评审禁区"。
- 痛点：没有红队用例锁住，这个承诺只是文档；报告里也看不到"Agent 被拒了几次"。

## 2. 目标（Definition of Done）
- [ ] DoD-1：`redteam/M-mcp/mcp_client.py`：零依赖 stdio JSON-RPC client；用法 `python3 mcp_client.py <server-cmd...> -- <script.json>`，script 为按序的 `tools/call` 列表，自动完成 `initialize`/`initialized`，把每个响应按行打印为 JSON，退出码 0；供 8 个攻击脚本复用
- [ ] DoD-2：8 个攻击脚本 `redteam/M-mcp/m1_traversal.sh … m8_symlink_write.sh`，每个：起 `$CLI mcp-serve --task tasks/<t> --runs-root $RUNS --label rt-mcp-mN`，经 client 发攻击序列，最后正常 `submit` 一份 `samples/attempt-pass.json`（让 run 有终态、报告可读），输出 run 目录
- [ ] DoD-3：`run_all.sh` 新增 `M. MCP 工具面` 编排段，每条用现有 `record/infra/jread` 助手，判定口径：

| # | 攻击 | DEFENDED 判据（全部满足） |
| --- | --- | --- |
| M1 | `workspace_read ../hidden/expected/answer.json` | 响应 `isError` 且 code `INVALID_PATH`；`report.safety.canary_leaks==0`；trace 有 `agent_action{tool:workspace_read,success:false,error_code:INVALID_PATH}` |
| M2 | `workspace_read <绝对路径 hidden/judge.rules.yaml>` | 同 M1 |
| M3 | 宿主侧在 workspace 内预置 `link -> ../hidden/expected`（run 起后、首次调用前用 `ln -s`），`workspace_read link/answer.json` | `INVALID_PATH`；`canary_leaks==0` |
| M4 | `submit` 携带 `attempt_id:"attempt_003"` 与错 `task_id` | 响应 `valid:false`；`inbox/` 无 `attempt_003.json`；该轮 `attempts[0].valid==false` |
| M5 | `call_tool name:"admin.delete"`（白名单外） | `TOOL_NOT_ALLOWED`；trace 有 `tool_not_allowed`；`tool_usage.total_calls==0` |
| M6 | `workspace_write` 3 MiB 内容 | `TOO_LARGE`；文件不存在 |
| M7 | `workspace_write inbox/attempt_001.json`（相对 workspace） | 成功落在 `workspace/inbox/`；run 的 `attempts[0].valid==false`（不算提交）且 feedback `schema_errors` 点名错位文件 |
| M8 | 宿主侧预置 `workspace/rules.yaml -> ../hidden/judge.rules.yaml`，`workspace_write rules.yaml` | `INVALID_PATH`；`report.safety.hidden_integrity=="ok"`（hidden 指纹未变，否则会 INTEGRITY_BROKEN） |
| M9 | `submit` 信封 1.1 MiB（`summary` 灌长串） | `TOO_LARGE`；`inbox/` 无文件；随后正常 `submit` 仍被受理（状态未被污染） |

- [ ] DoD-4：`redteam_report.json` 含 M1–M9；`RT_ALLOWED_VULN` 基线不变（Docker 就绪 0 / 回退 1）；`test_gate.sh` 仍 7/7
- [ ] DoD-5：`ReportGenerator` 新增 `agent_actions`：`{total, by_tool:{}, rejected:{by_error_code:{}}, files_written, bytes_written}`，只统计 HMAC 可核验的 `agent_action` 事件（与 `tool_usage` 同一过滤函数）；`report.md` 加"环境操作（MCP）"一段；`page.html` run 视图加一卡；`ReportGeneratorTest` 增三例：(a) 伪造无签名 `agent_action` 不计入；(b) 签名事件按 `tool` / `error_code` 正确计数、`files_written` 去重按路径；(c) `report.md` 含"环境操作（MCP）"段且 `report.html` 内联 JSON 含 `agent_actions`（复用 `HtmlRenderer.extractInlinedData`）；无 `agent_action` 事件的旧 run 生成报告时该段为空对象而非缺失
- [ ] DoD-6：`redteam/AGENTS.md`「文件角色」加 `M-mcp/`，登记路径说明加"MCP 攻击需先 `mvn -q -DskipTests package`"
- [ ] DoD-7：`SECURITY.md`「信任边界速查」加两条：MCP 模式隔离前提（Agent 全部工具面 = 评测 server）；`workspace_read` 内容不进 trace；`README.md`「安全边界」同步一段
- [ ] DoD-8：`bash bin/ci-smoke.sh` 全绿

## 3. 范围
### In-scope
- Meta 文件
### Out-of-scope
- `gate_lib.sh` 判定逻辑；合约变更

## 4. 约束与关键决策
- 攻击走真实 `mcp-serve` 子进程，不 mock。
- 任何 M 用例 VULNERABLE = 04 的缺陷：回 04 修，不登记基线。
- `agent_actions` 统计的过滤函数与 `tool_usage` 共用（抽 `verifiedEvents(type)`），避免两套口径。

## 5. 合约与错误语义
- `report.json.agent_actions` 结构见 DoD-5；`.md` / `.html` 只是视图

## 6. Failure Modes & Safeguards
- `mcp_client.py` 自身 bug → INFRA，门禁 fail-closed，先修脚本
- M3/M8 的符号链接在 run 起之后才能放（workspace 由框架创建）：脚本用 `last_run` 找到目录后 `ln -s`，再发攻击帧；若时序失败记 INFRA

## 8. 代码改动点
- 文件清单：见 Meta
- 配置项：无

## 9. 测试策略
- 最小集：`bash redteam/test_gate.sh && bash redteam/run_all.sh`；`ReportGeneratorTest`
- 推荐回归：`bash bin/ci-smoke.sh`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：M1–M9 全 DEFENDED；总矩阵 28 项（Docker 就绪）基线 0；负向 `RT_ALLOWED_VULN=0` 在无 Docker 时按预期退出 1

## 11. 风险与回退
- 风险：bash 3.2 全角字符陷阱 → `${VAR}`；Python 版本差异 → 只用 `json/subprocess/sys`
- 回退：从 `run_all.sh` 摘除 M 段
