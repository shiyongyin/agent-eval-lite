---
id: TASK-USE-09
title: 文档口径（usage / OTLP / world_state）与 ScriptJudge 超时测试收尾
module: docs / judge 测试
dependsOn: []
risk: low
status: done
featureFlag: none
dodCommands:
  - git diff --check
  - mvn -q -Dtest=ScriptJudgeTest test
  - rg -n "自报|观察副本|事实源" README.md SECURITY.md
  - rg -n "order_sensitive" tasks/AGENTS.md .agents/skills/ael-new-task/SKILL.md
---

# TASK-USE-09：文档口径与 ScriptJudge 超时测试收尾

## 0. Meta
- 语言/框架版本：Markdown；Java 17 测试 `ScriptJudgeTest`
- 影响模块/包前缀：`README.md`、`SECURITY.md`、`tasks/AGENTS.md`、`.agents/skills/ael-new-task/SKILL.md`、`src/test/java/com/agenteval/judge/ScriptJudgeTest.java`
- 最小验收命令：`git diff --check` + `ScriptJudgeTest`

## 1. 背景
- 现状：`research.md` §3 / §4.2 列出的边界声明中，“`usage` 自报不参与评分”“OTLP 导出是观察副本”只出现在 `docs/05` HTML、测试与 Javadoc；`world_state` 的 `scope` / `order_sensitive` / 多重集语义只在 `docs/04`、`docs/07` 与 `tool-call-001` hidden 规则；`ScriptJudgeTest` 缺超时用例。
- 痛点：任务作者按 AGENTS.md 路由读不到 `world_state` 语义；安全边界文档漏两条口径。

## 2. 目标（Definition of Done）
- [ ] `README.md` 安全边界 + `SECURITY.md`：加“`usage` 为 Agent 自报，仅做 ROI 参考，不参与评分，不防谎报”“OTLP / OpenInference 导出是观察副本；JSONL + HMAC trace 才是判分事实源”
- [ ] `tasks/AGENTS.md`、`.agents/skills/ael-new-task/SKILL.md`：补 `world_state` 的 `scope` / `order_sensitive` / 多重集语义（不复制任何 expected 值）
- [ ] `ScriptJudgeTest` 新增脚本超时用例，断言按评审设施故障处理

## 3. 范围
### In-scope
- 上述 5 个文件
### Out-of-scope
- 修改 judge 行为
- 手改 `docs/CODEMAP.md`

## 4. 约束与关键决策
- `world_state` 语义从 `docs/04`、`docs/07`、`tool-call-001/hidden/judge.rules.yaml` 注释提炼；hidden 内容不得复制到公开文档。
- 超时用例参考 `RulesJudge` command check 的超时语义，保持“判分失败 ≠ Agent 低分”的口径。

## 6. Failure Modes & Safeguards
- 超时测试在慢机器上 flaky → 用极小超时 + `sleep` 明显大于超时的脚本，避免边界值

## 8. 代码改动点
- 文件清单：见 In-scope

## 9. 测试策略
- 最小集：`ScriptJudgeTest`
- 推荐回归：`mvn -q verify`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：`rg` 均有命中；测试通过

## 11. 风险与回退
- 风险：无
- 回退：revert 单 commit
