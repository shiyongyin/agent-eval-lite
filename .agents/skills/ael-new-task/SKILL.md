---
name: ael-new-task
description: 在 AgentEval-Lite 仓库新增或修改评估任务（内置 tasks/<task-id>/ 或私有 evalsets/<set>/tasks/<task-id>/）。当用户要求"加一个评估任务"、"写 task.yaml"、"改 judge 规则"、"设计新的评测场景"时使用。
---

# AgentEval-Lite 任务编写工作流

权威规范：`tasks/AGENTS.md`（目录契约 + 硬约束 + 防泄露检查单）与 `docs/PLAYBOOK.md` recipe 4；check 类型语义见根 `README.md`「写一个新任务」。执行前先读这三处，不要凭记忆写。

**先定归属**：框架自证的示例任务才进内置 `tasks/`（会进 CI 门禁与 CODEMAP）；用户业务任务一律放 `evalsets/<set>/tasks/`（建集走 `ael-build-evalset`）。下文用 `<tasks-root>` 指代二者之一。

## 步骤

1. 脚手架起步（不要从空目录手搓）：`bin/agent-eval task init --id <kebab-case>-001 --tasks-root <tasks-root>`
2. 改 `task.yaml`：`task_id` 必须等于目录名；`scoring.dimensions` 权重之和必须等于 `max_score`；`tier` 归入 smoke/regression/security/domain；字段一律 snake_case。
3. 放 Agent 可见材料进 `work/`（`visible_context` 带 `work/` 前缀列出）；写 `agent_brief`（只描述任务与提交要求，不夹带答案线索）。**好任务要有区分度**：在材料里布设朴素做法会踩中的陷阱（干扰行、需跨文件推理的结论）——全员满分的任务度量不出任何东西。
4. 写 `hidden/judge.rules.yaml`：期望值放 `hidden/expected/` 并用 `expected_from: "expected/x.json#/指针"` 引用；`feedback_fail` 只说「哪里不对、往哪个方向查」，禁含期望值。check 类型选择：
   - 期望是精确值 → `jsonpath_equals`（首选）
   - 期望是关键点清单、允许部分得分 → `list_coverage`
   - 必须验证真实工具调用/终态 → 工具轨迹类 check（只认 HMAC 可核验事件）
   - 三者都不行的主观维度才用 `llm_rubric`（有效权重 ≤30%、禁 blocking，validate 会拦）
5. 更新 `samples/`：`attempt-pass.json`、`attempt-fail.json`（要犯真实 Agent 最可能犯的错）、`replay.yaml`（必须编排「第 1 轮失败 → 按反馈修正 → 第 2 轮通过」闭环）。
6. 防泄露自查（红线）：hidden 内容的任何拷贝/改写不得出现在 `work/`、`samples/`、`agent_brief`、`feedback_fail` 对外文案；工具凭证用 `${ENV:*}`，永不落任务文件。

## 判分规则快速迭代（把规则写准的最短回路）

改 `hidden/judge.rules.yaml` 后不必整轮 run，用离线 judge 直接对 samples 判分：

```bash
# samples 里 attempt_id 是 {attempt_id} 占位符（回放时才替换），离线判分前先代入，否则被 schema 拦
jq '.attempt_id="attempt_001"' <task-dir>/samples/attempt-fail.json > /tmp/s.json
bin/agent-eval judge --task <task-dir> --submission /tmp/s.json   # 工具轨迹类 check 需另配 --trace
```

预期：pass 样例满分、fail 样例恰好挂在你设计的 check 上。fail 样例判成通过（规则太松）或 pass 样例被扣分（规则太严/指针写错）都要回去改规则，直到两侧都符合预期再跑完整回放。

## 完成判据

两类任务都必须全绿：

```bash
bin/agent-eval validate --task <tasks-root>/<task-id>
bin/agent-eval run --task <tasks-root>/<task-id> --agent scripted \
    --script <tasks-root>/<task-id>/samples/replay.yaml   # 私有集加 --runs-root evalsets/<set>/runs
```

**仅内置 `tasks/`** 追加（私有 evalset 任务不需要）：

```bash
bin/agent-eval suite --tasks-root tasks --fail-on-not-passed
mvn -q test
bash bin/gen-codemap.sh   # 内置任务库表进 CODEMAP，忘了会挂漂移门禁（只索引 tasks/，不含 evalsets）
```

任务要进 smoke/regression 门禁前，用 `ael-review-task-quality` 审查；replay 能过 ≠ 有资格进门禁。
