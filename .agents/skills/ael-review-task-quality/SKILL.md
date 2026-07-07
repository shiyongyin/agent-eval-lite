---
name: ael-review-task-quality
description: 审查 AgentEval-Lite 评估任务质量，判断任务能否进入私有 evalset 的 smoke/regression 门禁。当用户说"审查任务质量"、"这个任务能不能进测评集"、"检查 hidden 是否泄露"、"评估 task.yaml/judge 规则质量"时使用。
---

# 评估任务质量审查

你（AI）在这个工作流里是「任务质量审查员」：只审查任务资产是否适合进入团队评测集，不替用户改业务答案。审查对象可以是内置 `tasks/<task-id>/`，也可以是私有 `evalsets/<set>/tasks/<task-id>/`。

权威规范：根 `AGENTS.md` 安全红线、`tasks/AGENTS.md` 目录契约、`docs/07-任务质量清单.md`。如需新增/修改任务，切到 `ael-new-task`；如需跑门禁，收尾用 `ael-verify`。

## 审查步骤

1. **定位任务目录**：确认存在 `task.yaml`、`work/`、`hidden/judge.rules.yaml`、`samples/attempt-pass.json`、`samples/attempt-fail.json`、`samples/replay.yaml`。
2. **读公开面**：读 `task.yaml`、`work/`、`samples/`，确认 Agent 可见材料能独立支撑作答，`agent_brief` 不夹带答案线索。
3. **读隐藏面**：读 `hidden/judge.rules.yaml` 和 `hidden/expected/`，确认 expected 只经 `expected_from` 引用，`feedback_fail` 不泄露答案、规则细节或 mock 响应。
4. **检查区分度**：fail 样例必须犯真实 Agent 可能犯的错；pass 样例必须覆盖满分路径；replay 应体现「失败 → 反馈 → 修正通过」。
5. **检查可复现性**：优先确定性 check；`llm_rubric` 只能是低权重、非 blocking；涉及命令/工具/终态的任务要有可复核证据。
6. **运行最小验证**：

```bash
bin/agent-eval validate --task <task-dir>
bin/agent-eval run --task <task-dir> --agent scripted \
    --script <task-dir>/samples/replay.yaml
```

## 质量判定

给出一个明确等级：

| 等级 | 含义 |
| --- | --- |
| `ready:smoke` | 任务快、稳定、覆盖核心能力，适合每次改 Agent 必跑 |
| `ready:regression` | 任务有区分度但成本较高或业务专项，适合合并前/发版前跑 |
| `needs-work` | 结构基本可用，但存在样例、反馈、规则或材料问题 |
| `reject` | 泄露 hidden、不可复现、无法判分或任务目标不清 |

## 输出格式

先给结论，再给证据：

```text
结论：needs-work
建议分层：regression

主要问题：
- [高] feedback_fail 泄露 expected 关键值：hidden/judge.rules.yaml:...
- [中] attempt-fail.json 不是高概率真实错误，区分度弱：samples/attempt-fail.json:...

已验证：
- bin/agent-eval validate --task ...：通过/失败
- scripted 回放：通过/失败

进入门禁前必须修复：
- ...
```

## 红线

- 不把 `hidden/expected`、judge 私有规则、mock 响应复制进公开说明、反馈文案、README、PR 描述或给 Agent 的建议。
- 对外只引用 `rule_id`、维度、公开 feedback 文案和验证命令结果。
- 如果审查中发现 hidden 泄露，直接判 `reject`，并建议重置该任务的 expected / canary / 样例。
