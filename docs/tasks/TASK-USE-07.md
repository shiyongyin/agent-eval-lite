---
id: TASK-USE-07
title: 团队 evalset 第一批 5 个业务 smoke 任务
module: evalsets/<team-set>（团队侧）
dependsOn: [TASK-USE-01, TASK-USE-02]
risk: medium
featureFlag: none
dodCommands:
  - for d in evalsets/<team-set>/tasks/*/; do bin/agent-eval validate --task "$d"; done
  - bin/agent-eval suite --tasks-root evalsets/<team-set>/tasks --runs-root evalsets/<team-set>/runs --tier smoke --agent scripted --repeat 2 --fail-on-not-passed
---

# TASK-USE-07：团队 evalset 第一批 5 个业务 smoke 任务

## 0. Meta
- 语言/框架版本：task.yaml / hidden 规则 / samples（无 Java 改动）
- 影响模块/包前缀：`evalsets/<team-set>/tasks/*`（不入本仓库内置 `tasks/`）
- 最小验收命令：见 `dodCommands`（`<team-set>` 替换为实际集合 id）

## 1. 背景
- 现状：内置 5 任务是通用能力题；`evalsets/demo-ops-agent` 是演示集。
- 痛点：“投入使用”需要靶向团队自己 Agent 的任务；框架侧只能提供脚手架与质量门槛。

## 2. 目标（Definition of Done）
- [ ] `bin/agent-eval evalset init --id <team-set>` 建集
- [ ] 5 个任务经 `task init` 脚手架生成，`tier: smoke`，业务材料 / hidden 规则 / samples 替换完成
- [ ] 每个任务通过 `ael-review-task-quality`（`docs/07` 清单）
- [ ] 每个任务 scripted fail→pass 闭环通过
- [ ] 每个任务至少一次真实 Agent run（用 TASK-USE-02 预设）

## 3. 范围
### In-scope
- `evalsets/<team-set>/`（tasks、agents.yaml、scripts、README）
### Out-of-scope
- 修改内置 `tasks/`
- 修改框架代码（发现的问题交对应卡片）

## 4. 约束与关键决策
- 遵守 `tasks/AGENTS.md` 与 `docs/07`：hidden 不泄露、`feedback_fail` 只给方向、优先确定性 check、`llm_rubric` 低权重非 blocking。
- 工作流：`ael-build-evalset` → `ael-new-task` → `ael-review-task-quality` → `ael-verify`。

## 6. Failure Modes & Safeguards
- 任务无区分度（朴素 Agent 也过）→ 不进 smoke，退回打磨
- hidden 材料出现在 `work/` / `samples/` → 审查阻断

## 8. 代码改动点
- 文件清单：`evalsets/<team-set>/**`

## 9. 测试策略
- 最小集：`validate` × 5；scripted suite `--fail-on-not-passed`
- 推荐回归：真实 Agent `suite --agents-file --repeat 2`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：5/5 validate 通过；scripted 基线 100% pass^k；至少一份真实 Agent 报告

## 11. 风险与回退
- 风险：业务材料含敏感数据 → 脱敏后再入 evalset；私有集不进公开仓库
- 回退：删除该 evalset 目录
