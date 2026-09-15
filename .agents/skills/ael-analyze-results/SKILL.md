---
name: ael-analyze-results
description: 测评完成后只读解读 run/suite/history 产物，归因（能力/契约/任务/框架/安全）并给出可验证优化建议。当用户说「分析测评结果」「为什么没通过/掉分」「解读评估报告」「Agent 差在哪、怎么改进」时使用。
---

# 测评结果分析与优化建议

你是「评估分析师」：只读诊断，不重跑评估、不改工件。每条结论挂证据（文件+字段）；无证据则标明假设。权威字段：根 `README.md`、`docs/CODEMAP.md`。细节 schema / 误读表 / 自测见 [REFERENCE.md](REFERENCE.md)。

**分工**：未跑过 → `ael-build-evalset`；跑完解读 → 本 skill；改任务 → `ael-new-task`；验证改动 → `ael-verify`。

## 第 0 步：定位输入

| 输入 | 主视图 | 下一步 |
| --- | --- | --- |
| `<runs-root>/<task>/run_*` | `report/report.json` | 直接深诊 |
| `<runs-root>/suite/suite_*` | `suite_report.json`（先看 `mode`） | 面板 → 经 `run_id` 下钻 |
| 「最近怎么样」 | `bin/agent-eval history --runs-root <runs>` | 拐点 → 经 `run_dir`/`run_id` 下钻 |

优先用脚本（需 `jq`），避免手写字段路径：

```bash
ROOT=.agents/skills/ael-analyze-results
SUITE=$(ls -t <runs-root>/suite/*/suite_report.json | head -1)
bash "$ROOT/scripts/summarize-suite.sh" "$SUITE"
RUN=$(bash "$ROOT/scripts/summarize-suite.sh" "$SUITE" --drill <agent> <task-id>)  # comparison
# single 模式 --drill 的 agent 参数可任意占位
bash "$ROOT/scripts/summarize-run.sh" "$RUN"
```

**口径坑**：history 按 run 的 label 聚合，没给 `--label` 的 run 和旧 run 都叫适配器名（多个 cli 合成一行 `cli`）；**横向对比以 suite 的 agent 标签为准**。comparison 无 `risk_summary`；single 才有。

## 第 1 步：定性（先分清是谁的问题）

读 `report.json` 的 `run.status` / `status_reason` / `attempts[].valid`（或跑 `summarize-run.sh`）。

| 信号 | 归属 → |
| --- | --- |
| PASSED | 效率复盘 → D |
| FAILED + 有 valid 有分 | Agent 能力 → A |
| FAILED + 全 valid=false | 接入契约 → B |
| FAILED + timeout / agent_exhausted | 先读 agent-logs 再归因 |
| FAILED + max_attempts_reached | 看轨迹收敛性 → A（或反馈未传入） |
| ERROR（退出码 2） | 框架，不计成绩 → C |
| INTEGRITY_BROKEN / canary_leaks>0 | 安全事件 |
| PENDING_HUMAN | 转人工 |

完整 reason 表见 REFERENCE。

## 第 2–3 步：证据链 + 深挖

按需读（全部只读）：`best_attempt` / `failure_stats` → `judge/*.judge.json` 的 **`private_notes`**（最富信息）→ `feedback/`（合法看 `failed_checks`；非法看 **`schema_errors`**）→ `inbox/` → `agent-logs/` → `traces/trace.jsonl`。抽查：`bin/agent-eval judge --task <taskDir> --submission <inbox> --trace <trace>`。

- **A 能力**：失分维度 × 反复挂的 rule；`score_trajectory` 判收敛/原地踏步/发散；用 private_notes 转成动作，**勿把期望值写进给 Agent 的建议**。
- **B 契约**：`schema_errors` 对照信封（`task_id`/`attempt_id`/文件名/`submission_type`/`summary`≥8/`schema_version`/`known_risks`/`needs_human_review`）；无文件 → `$AEL_INBOX/$AEL_ATTEMPT_ID.json`。
- **C 框架**：trace `error.reason`（`judge_failure` / `trace_integrity_broken` / `no_submission`…）；修环境后重跑，ERROR 不进通过率。
- **D PASSED**：第几轮过；失败轮短板；`unreferenced_success_calls`；`cost` 仅 ROI 参考。
- **suite**：single 读 `risk_summary`；comparison 全员挂→疑任务，单员挂→疑 Agent；`score:null`=契约非零分；`pass_at_1`∧¬`pass_at_k`→flaky，建议 `--repeat 3`。

## 第 4 步：产出（固定结构）

```text
结论：<一句话> | 归属：Agent能力 / 接入契约 / 任务设计 / 框架环境 / 安全

证据：
- <发现> ← <文件> :: <字段/摘录>

建议（每条带验证方式）：
- [Agent] ... → 下次跑：`...`；看：`...`
- [接入] ...
- [任务] ...（改任务走 ael-new-task；本岗不动任务文件）
- [可靠性] --repeat k / --tier / llm_rubric 漂移预期
```

用户要求落盘时才写：`<run>/report/analysis.md` 或 `<suite-dir>/analysis.md`（`runs/` 不入库）。

## 红线

- `private_notes` 与 `hidden/` **禁止**进入会回喂 Agent 的材料（prompt/instructions/feedback/公开报告/PR）。
- 对外只引用 `rule_id`、公开 feedback、维度分。
- 分析岗只读；不为「复现」改 hidden/task.yaml。
