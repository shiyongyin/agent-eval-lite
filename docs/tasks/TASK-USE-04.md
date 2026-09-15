---
id: TASK-USE-04
title: --label 进入 meta/report/trace，history 按 label 分组
module: runner / state / report / trace / cli
dependsOn: []
risk: medium
status: done
featureFlag: none
dodCommands:
  - bash bin/gen-codemap.sh --check
  - mvn -q verify
  - bin/agent-eval suite --tasks-root tasks --fail-on-not-passed
  - bash redteam/test_gate.sh && bash redteam/run_all.sh
---

# TASK-USE-04：--label 进入 meta/report/trace，history 按 label 分组

## 0. Meta
- 语言/框架版本：Java 17 / Maven / Jackson / JUnit 5
- 影响模块/包前缀：`com.agenteval.{runner,state,report,trace,cli}`
- 最小验收命令：`mvn -q verify`

## 1. 背景
- 现状：`RunMeta.agentName` 取 `adapter.name()`（cli 恒为 `"cli"`）；`report.json` 的 `run.agent` 与 `history` 的 `(task_id, agent)` 分组都用它；suite 的 `--label` 只进 `suite_report`。
- 痛点：current 与 candidate 两个 cli Agent 在 `history` 里合并成一行，团队最关心的“新版本比旧版本好坏”看不出来。

## 2. 目标（Definition of Done）
- [ ] `RunManager.RunConfig` 增 `label`（可空）；`run` 命令增 `--label`；`suite` 传入 `AgentSpec.label`
- [ ] `RunMeta` 增 `agentAdapter`；`agentName` = label 非空取 label，否则取 `adapter.name()`
- [ ] `RUN_STARTED` trace 事件 `agent` 为 label、新增 `adapter`；`schemas/trace.event.schema.json` 同步
- [ ] `report.json`：`run.agent` = label，新增 `run.adapter`；`report.md` “Agent / 模型”行展示 label
- [ ] `history` 分组键代码不变，语义变为按 label；`--agent` 过滤按 label
- [ ] 旧 `meta.json`（缺 `agentAdapter`）可读，report 回退显示 `agentName`
- [ ] `.agents/skills/ael-analyze-results/REFERENCE.md` 更新 history 口径
- [ ] `docs/CODEMAP.md` 重新生成

## 3. 范围
### In-scope
- `runner/RunManager.java`、`runner/SuiteRunner.java`、`state/RunMeta.java`、`cli/RunCommand.java`
- `report/ReportGenerator.java`、`trace/OtlpTraceExporter.java`、`src/main/resources/schemas/trace.event.schema.json`
- 测试：`HistoryCommandTest`、`ReportGeneratorTest`、`SuiteRunnerTest`、`TraceLoggerTest`、`OtlpTraceExporterTest`
- `.agents/skills/ael-analyze-results/REFERENCE.md`
### Out-of-scope
- 改 `history` 输出格式或分组键
- 迁移旧 run 目录

## 4. 约束与关键决策
- 依赖方向不变：`cli -> runner -> {report, state, trace}`。
- `Jsons` 已 `FAIL_ON_UNKNOWN_PROPERTIES=false`，新字段可选。
- label 为空时行为与现状完全一致，红队用例与现有测试不受影响。
- trace 新字段必须同步 schema、签名测试、导出测试（AGENTS.md 可信链要求）。

## 5. 合约与错误语义
- `meta.json`：`agent_name`（语义变更为 label 优先）、新增 `agent_adapter`
- `report.json`：`run.agent`（label）、新增 `run.adapter`
- trace `RUN_STARTED`：`agent`（label）、新增 `adapter`

## 6. Failure Modes & Safeguards
- 旧 run 缺 `agent_adapter` → null → report 显示 `agent_name`，不抛错
- label 含路径分隔符或空白 → `RunCommand` 校验拒绝（与 suite label 规则一致）

## 8. 代码改动点
- 文件清单：见 In-scope
- 配置项：`--label`（run）

## 9. 测试策略
- 最小集：`HistoryCommandTest` 新增“两个 label 不同的 cli run 分两行 + 旧格式夹具仍可读”；`ReportGeneratorTest` 断言 `run.agent` / `run.adapter`；`SuiteRunnerTest` 断言下钻 run 的 `meta.json.agent_name` 等于 label；trace schema / 导出测试
- 推荐回归：`bash redteam/run_all.sh`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全部 exit 0；`bin/agent-eval history --runs-root <多 label runs>` 输出按 label 分行

## 11. 风险与回退
- 风险：外部消费 `report.json.run.agent` 的脚本语义变化 → 在 CHANGELOG 记录
- 回退：revert 单 commit；旧 run 不受影响
