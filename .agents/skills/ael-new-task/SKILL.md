---
name: ael-new-task
description: 在 AgentEval-Lite 仓库新增或修改评估任务（tasks/<task-id>/）。当用户要求"加一个评估任务"、"写 task.yaml"、"改 judge 规则"、"设计新的评测场景"时使用。
---

# AgentEval-Lite 任务编写工作流

权威规范：`tasks/AGENTS.md`（目录契约 + 硬约束 + 防泄露检查单）与 `docs/PLAYBOOK.md` recipe 4；check 类型语义见根 `README.md`「写一个新任务」。执行前先读这三处，不要凭记忆写。

## 步骤

1. 脚手架起步（不要从空目录手搓）：`bin/agent-eval task init --id <kebab-case>-001`
2. 改 `task.yaml`：`task_id` 必须等于目录名；`scoring.dimensions` 权重之和必须等于 `max_score`；`tier` 归入 smoke/regression/security/domain；字段一律 snake_case。
3. 放 Agent 可见材料进 `work/`；写 `agent_brief`（只描述任务，不夹带答案线索）。
4. 写 `hidden/judge.rules.yaml`：期望值放 `hidden/expected/` 并用 `expected_from: "expected/x.json#/指针"` 引用；优先确定性 check，`llm_rubric` 只用于低权重主观维度（有效权重 ≤30%、禁 blocking，validate 会拦）。
5. 更新 `samples/`：`attempt-pass.json`、`attempt-fail.json`、`replay.yaml`（必须编排「第 1 轮失败 → 按反馈修正 → 第 2 轮通过」闭环）。
6. 防泄露自查（红线）：hidden 内容的任何拷贝/改写不得出现在 `work/`、`samples/`、`agent_brief`、`feedback_fail` 对外文案；工具凭证用 `${ENV:*}`，永不落任务文件。

## 完成判据（全绿才算完）

```bash
bin/agent-eval validate --task tasks/<task-id>
bin/agent-eval run --task tasks/<task-id> --agent scripted --script tasks/<task-id>/samples/replay.yaml
bin/agent-eval suite --tasks-root tasks --fail-on-not-passed
mvn -q test
bash bin/gen-codemap.sh   # 任务库表进 CODEMAP，忘了会挂漂移门禁
```
