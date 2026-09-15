---
id: TASK-USE-01
title: 真实 Agent 实跑（dogfooding）并产出问题清单
module: runs / docs
dependsOn: [TASK-USE-00]
risk: medium
status: done
featureFlag: none
dodCommands:
  - bin/agent-eval suite --tasks-root tasks --runs-root runs/dogfood --agent cli --cmd 'bash evalsets/_template/scripts/run-agent.sh claude' --label claude-code --repeat 3
  - bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks --runs-root runs/dogfood --agent cli --cmd 'bash evalsets/_template/scripts/run-agent.sh claude' --label claude-code --repeat 3
  - git diff --check
---

# TASK-USE-01：真实 Agent 实跑（dogfooding）并产出问题清单

## 0. Meta
- 语言/框架版本：Java 17 CLI（已构建 jar）+ 真实 Agent CLI（Claude Code 或 Codex CLI）
- 影响模块/包前缀：不改代码；产物在 `runs/dogfood/`（不入库），结论写入 `docs/08` 附录
- 最小验收命令：见 `dodCommands`；`--cmd` 可在 TASK-USE-02 未完成时用临时命令替代

## 1. 背景
- 现状：本地 177 个 run 的 `meta.json` 中真实 LLM Agent 为 0（scripted 155、红队/测试 cli 20、docker 2）。
- 痛点：README / docs/06 的小团队路线、多轮反馈修正、current vs candidate 横评全部面向真实 Agent，但从未被真实 Agent 验证。

## 2. 目标（Definition of Done）
- [ ] `runs/dogfood/suite/*/suite_report.json` 至少一份 `mode=single`，`suite.agent` 为真实 Agent label
- [ ] 内置 5 任务 + `demo-ops-agent` 2 任务均有真实 Agent run
- [ ] `docs/08-投入使用路线设计.md` 新增附录“R1 实跑记录”，每个观察点有结论 + 证据路径
- [ ] 附录末尾给出对 TASK-USE-02..09 排序的影响（保持 / 提前 / 推后，各一句理由）

## 3. 范围
### In-scope
- 用 cli 适配器跑真实 Agent；Docker 就绪时加 `--sandbox docker --sandbox-image <TASK-USE-03 镜像>`
- 用 `.agents/skills/ael-analyze-results/scripts/` 逐 run 定性
### Out-of-scope
- 修改任何 `hidden/` 规则或 expected 去“迎合” Agent
- 修改框架代码（发现的问题记入清单，交对应卡片）

## 4. 约束与关键决策
- 观察点固定为五项：`score_trajectory` 是否收敛；`invalid_submissions` 占比；`status=ERROR` 数量与原因；每轮耗时 vs `attempt_timeout_minutes`；Agent 是否完整读取 `instructions.md`（看 `agent-logs/`）。
- 模型凭证只经环境变量传入，不写入任何文件。

## 6. Failure Modes & Safeguards
- Agent 未写 inbox → 记为接入问题，不算能力问题（`valid=false` 全轮次）。
- `status=ERROR` → 框架/环境故障，单列，不计入 pass_rate 解读。
- 模型 API 不可达 → 记 INFRA，重跑一次仍失败则标注环境限制。

## 7. 可观测性 & 运维
- 排障路径：`runs/dogfood/<task>/<run>/agent-logs/`、`feedback/`、`traces/trace.jsonl`

## 8. 代码改动点
- 文件清单：`docs/08-投入使用路线设计.md`（附录）

## 9. 测试策略
- 最小集：无代码改动；`git diff --check`

## 10. 验收标准
- 命令：`ls runs/dogfood/suite/*/suite_report.json`；`rg "R1 实跑记录" docs/08-投入使用路线设计.md`
- 期望结果：均有输出

## 11. 风险与回退
- 风险：实跑结论推翻部分排序 → 允许，回写 `docs/08` §3
- 回退：删除 `runs/dogfood/`，撤销附录
