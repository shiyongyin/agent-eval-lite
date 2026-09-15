# ael-analyze-results 参考附录

按需加载；主流程见 [SKILL.md](SKILL.md)。字段语义权威：根 `README.md`、`docs/CODEMAP.md`。

## suite 两种模式的结构

| 字段 | single（`--agent`） | comparison（`--agents-file`） |
| --- | --- | --- |
| 汇总 | `.suite` | `.agents[]` |
| 任务结果 | `.results[]` | `.agents[].results[]` |
| risk_summary | 有（见下） | **无**（按 agent 横比矩阵） |
| 下钻 | `.results[].runs[].run_id` | `.agents[].results[].runs[].run_id` |

`risk_summary`（仅 single）：`not_passed_tasks` / `setup_error_tasks` / `flaky_tasks` / `failed_rules_by_id` / `action_required`。

## history 口径

- 按 `(task_id, agent_adapter_name)` 聚合；多个 `--label` 不同的 cli 在 history 里共享 `agent:"cli"`。
- **横向选型看 suite 标签**；history 只看单 Agent 纵向。
- `.trends[]`：`count` `pass_rate` `first_score` `last_score` `best_score` `deterministic` `latest_status`
- `.runs[]`：`run_id` `task_id` `agent` `model` `status` `score` `run_dir`（相对 `--runs-root`，可拼回目录）

## status / status_reason 全表

| status + 特征 | 定性 | 归属 |
| --- | --- | --- |
| PASSED + reason=`passed` | 通过；做效率复盘 | 分支 D |
| FAILED + 存在 `valid=true` 有分轮次 | 能力不足 | 分支 A |
| FAILED + 全部 `valid=false` | 提交契约问题 | 分支 B |
| FAILED + `max_attempts_reached` | 用尽轮次；看轨迹判收敛 | A（或接入未传反馈） |
| FAILED + `single_submit_not_passed` | 单轮模式未过 | A |
| FAILED + `timeout` | 超时 | Agent/接入 |
| FAILED + `agent_exhausted` | Agent 主动放弃（如 HTTP 204） | Agent/接入 |
| ERROR + `judge_failure:*` | 评审故障（常为 llm_rubric 未配模型，fail-closed） | 框架 C |
| ERROR / INTEGRITY_BROKEN + `trace_integrity_broken:*` | 签名链破坏 | 安全 |
| INTEGRITY_BROKEN + hidden 被改 | 结果不可信 | 安全事件 |
| PENDING_HUMAN + `agent_requested_human_review` | 请求人工复核 | 转人工 |

CLI 退出码：0 完成（无论是否通过）/ 1 参数错 / **2 框架故障或完整性熔断** / 3 配了 `--fail-on-not-passed` 且未过。

## 证据链速查

| 问题 | 读 | 命令 |
| --- | --- | --- |
| 维度/规则失分 | `report.json` → `best_attempt.*` / `failure_stats.by_rule` | `scripts/summarize-run.sh <run>` |
| 为何挂（最富信息） | `judge/<id>.judge.json` → `private_notes` | 脚本会打印；格式 `RULE [PASS/FAIL s/m] 详情` |
| 合法提交反馈 | `feedback/<id>.feedback.json` → `feedback` `failed_checks` `dimension_scores` | |
| 非法提交反馈 | 同文件 `valid:false` + **`schema_errors`**（字符串数组） | |
| 交了什么 | `inbox/<id>.json` | |
| 过程日志 | `agent-logs/<id>.log`（仅 cli/docker） | 读尾部 |
| 框架错误 | `traces/trace.jsonl` | `rg '"type":"(error\|submission_invalid)"'` |
| 题面 | `instructions.md` | |
| 复现判分 | | `bin/agent-eval judge --task <taskDir> --submission <inbox> --trace <trace.jsonl>` |

`jq` 缺失时读 `report.md` / `suite_report.md`；`bin/agent-eval` 需 jar（`mvn -q -DskipTests package`）。

## 收敛性判读

`score_trajectory` + 逐轮 `failed_rule_ids`：

- **收敛**：分上行、失败集缩小 → 可加 `max_attempts`
- **原地踏步**（如 `[60,60,60]` 同 rule）→ 先查 `AEL_FEEDBACK` / instructions 是否喂全，再看 `feedback_fail` 可操作性
- **发散**：越改越差 → 多轮策略问题

## 常见误读

| 现象 | 误读 | 正解 |
| --- | --- | --- |
| suite `score:null` | 0 分 | 无有效提交（契约） |
| 退出码 2 / ERROR | Agent 差 | 框架故障，不计成绩 |
| `pass_at_1` 过、`pass_at_k` 挂 | 已达标 | flaky |
| `untrusted_trace_events>0` | 可忽略 | 可疑/伪造工具事件 |
| `safety.canary_leaks>0` | 可忽略 | 接触 hidden 诱饵 |
| auto_eval 采样分 | 正式成绩 | 不参与成绩 |
| llm_rubric 分数漂 | 判分坏了 | `deterministic=false` 预期（权重≤30%） |
| `max_attempts_reached` | 系统异常 | 正常用尽轮次 |
| history 同行 `agent=cli` | 两个 Agent | adapter 同名；横向用 suite |

## 自测（维护本 skill 时）

```bash
ROOT=.agents/skills/ael-analyze-results
mvn -q -DskipTests package

bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks \
  --runs-root evalsets/demo-ops-agent/runs \
  --agents-file evalsets/demo-ops-agent/agents.yaml

SUITE=$(ls -t evalsets/demo-ops-agent/runs/suite/*/suite_report.json | head -1)
bash "$ROOT/scripts/summarize-suite.sh" "$SUITE"
# 预期 comparison：baseline/smart 全过，naive 0/2

RUN=$(bash "$ROOT/scripts/summarize-suite.sh" "$SUITE" --drill naive-ops log-triage-001)
bash "$ROOT/scripts/summarize-run.sh" "$RUN"
# 预期 FAILED + max_attempts_reached + trajectory [60,60,60] + ERROR_COUNT_CORRECT
# private_notes 含「实际=7 期望=6」——分析结论可提 rule_id，勿把期望值写进给 Agent 的建议

bin/agent-eval judge --task evalsets/demo-ops-agent/tasks/log-triage-001 \
  --submission "$RUN/inbox/attempt_001.json" \
  --trace "$RUN/traces/trace.jsonl"

bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks \
  --runs-root evalsets/demo-ops-agent/runs \
  --agent cli --cmd "bash $PWD/evalsets/demo-ops-agent/agents/smart_ops_agent.sh" \
  --label smart-ops --repeat 2
SINGLE=$(ls -t evalsets/demo-ops-agent/runs/suite/*/suite_report.json | head -1)
bash "$ROOT/scripts/summarize-suite.sh" "$SINGLE"
# 预期 single + risk_summary.action_required=false

bin/agent-eval history --runs-root evalsets/demo-ops-agent/runs
```

预期结论对齐 `evalsets/demo-ops-agent/README.md`：naive 日志 60（grep 陷阱）、审计 15、三轮不涨分。
