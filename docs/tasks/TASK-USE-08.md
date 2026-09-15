---
id: TASK-USE-08
title: PENDING_HUMAN 的 suite 口径与人工复核留痕
module: runner / docs
dependsOn: []
risk: low
featureFlag: none
dodCommands:
  - mvn -q -Dtest=SuiteRunnerTest test
  - bin/agent-eval suite --tasks-root tasks --fail-on-not-passed
  - git diff --check
---

# TASK-USE-08：PENDING_HUMAN 的 suite 口径与人工复核留痕

## 0. Meta
- 语言/框架版本：Java 17 / Maven / JUnit 5
- 影响模块/包前缀：`com.agenteval.runner.SuiteRunner`、`docs/06`、`README.md`
- 最小验收命令：`mvn -q -Dtest=SuiteRunnerTest test`

## 1. 背景
- 现状：Agent 提交 `needs_human_review:true` 时 `RunManager` 置 `RunStatus.PENDING_HUMAN`；`report.md` 有“人工复核请求”一行。
- 痛点：suite 如何对待、人工确认后如何放行、是否计入 pass_rate 均无定义与文档；`docs/04` 将“人工复核通道”列为余项。

## 2. 目标（Definition of Done）
- [ ] `PENDING_HUMAN` 在 suite 中计入“未稳定通过”，`all_passed=false`
- [ ] `risk_summary` 新增 `pending_human_tasks`（single 模式）；`suite_report.md` 小团队操作摘要增一项
- [ ] 人工复核留痕约定：run 目录 `review/decision.json`（`decision` / `reviewer` / `reason` / `reviewed_at`），不改原 `report.json`
- [ ] 文档写清离线重判入口 `bin/agent-eval judge --task <taskDir> --submission <run>/inbox/<attempt>.json --trace <run>/traces/trace.jsonl`
- [ ] `docs/06` “看报告”表增一行；`README.md` 报告一节说明
- [ ] `SuiteRunnerTest` 新增 PENDING_HUMAN 用例

## 3. 范围
### In-scope
- `src/main/java/com/agenteval/runner/SuiteRunner.java`
- `src/test/java/com/agenteval/integration/SuiteRunnerTest.java`
- `docs/06-小团队落地指南.md`、`README.md`
### Out-of-scope
- 审批流、账号、通知
- 自动把 PENDING_HUMAN 转 PASSED
- 修改 `RunManager` 终态逻辑

## 4. 约束与关键决策
- 非确定性 / 人工信号不得自动改变判分结果（与 `llm_rubric` 禁 blocking 同一原则）。
- `review/decision.json` 只是留痕，report 不读取它。

## 5. 合约与错误语义
- `suite_report.json`：`risk_summary.pending_human_tasks: string[]`
- `review/decision.json`：`{"decision":"accept|reject","reviewer":"...","reason":"...","reviewed_at":"ISO-8601"}`

## 6. Failure Modes & Safeguards
- comparison 模式无 `risk_summary` → 在 agent 矩阵中以状态列体现，不新增结构

## 8. 代码改动点
- 文件清单：见 In-scope

## 9. 测试策略
- 最小集：`SuiteRunnerTest` 用 scripted 回放产生 `needs_human_review:true` 的 run，断言 `all_passed=false`、`pending_human_tasks` 非空、md 含摘要项
- 推荐回归：`bin/agent-eval suite --tasks-root tasks --fail-on-not-passed`（内置任务不受影响）

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全部 exit 0

## 11. 风险与回退
- 风险：无
- 回退：revert 单 commit
