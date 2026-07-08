---
name: ael-analyze-results
description: 测评完成后 AI 辅助解读评估产物（run/suite/history），定位问题归属并给出可验证的优化建议。当用户说"分析测评结果"、"为什么没通过/掉分"、"解读评估报告"、"Agent 差在哪、怎么改进"时使用。
---

# 测评结果分析与优化建议

你（AI）在这个工作流里是「评估分析师」：只读诊断既有产物，不重跑评估、不改任何工件。每条结论都必须挂证据（文件+字段）；没有证据支撑的判断要明说是假设。字段语义权威：根 `README.md`（报告与命令）、`docs/CODEMAP.md`（report/state 包）。

**与相邻 skill 的分工**：`ael-build-evalset` 负责建集与实跑；本 skill 只在已有 `runs/` 产物上解读。用户还没跑过评估 → 先走 build-evalset；跑完要读报告 → 走本 skill。

---

## 第 0 步：定位输入形态

| 用户给的 | 主视图 | 粒度 |
| --- | --- | --- |
| 单次 run 目录 `<runs-root>/<task-id>/run_*` | `report/report.json`（`report.md` 是人读版） | 单任务单 Agent 深诊 |
| 套件目录 `<runs-root>/suite/suite_*` | `suite_report.json`（先判 `mode`） | 多任务横向 / 多 Agent 对比 |
| 趋势诉求（"最近怎么样"） | `bin/agent-eval history --runs-root <runs>`（只读聚合，可放心重跑） | 跨 run/Agent/版本走势 |

suite/history 锁定可疑点后，一律经 `run_id` 下钻到单 run 目录做深诊。

### 快速定位最新产物

```bash
# 最新 suite 报告
SUITE=$(ls -t <runs-root>/suite/*/suite_report.json | head -1)
MODE=$(jq -r '.mode' "$SUITE")

# comparison 模式（--agents-file 多 Agent 对比）
# 字段路径：.agents[].results[].runs[].run_id
jq -r '.agents[] | [.agent, .all_passed, (.pass_rate*100|tostring+"%"), .total] | @tsv' "$SUITE"

# single 模式（--agent 单 Agent 跑全集）
# 字段路径：.suite (汇总) + .results[].runs[].run_id
jq -r '[.suite.agent, .suite.all_passed, (.suite.pass_rate*100|tostring+"%"), .suite.total] | @tsv' "$SUITE"
jq '.risk_summary' "$SUITE"

# 某任务最新 run
ls -td <runs-root>/<task-id>/run_* | head -1
```

### suite 两种模式的结构差异

| 字段 | single 模式 | comparison 模式 |
| --- | --- | --- |
| 汇总 | `.suite` (单 Agent 聚合) | `.agents[]` (每 Agent 一个) |
| 任务结果 | `.results[]` | `.agents[].results[]` |
| risk_summary | `.risk_summary`（含 `not_passed_tasks` `setup_error_tasks` `flaky_tasks` `failed_rules_by_id` `action_required`） | 不存在（需按 agent 逐个分析） |

### history 与 suite 的口径差

`history` 按 `(task_id, agent_adapter_name)` 聚合——多个 cli Agent（`--label` 不同）在 history 里共享 `agent: "cli"` 行。**多 Agent 横向对比必须以 suite_report 的 agent 标签为准**，history 只适合看单 Agent 纵向趋势。

history JSON 结构（命令落盘在 `<runs-root>/history/history_*/history.json`，stdout 是摘要）：
- `.trends[]`：按 (task_id, agent) 聚合的趋势，含 `count` `pass_rate` `first_score` `last_score` `best_score` `deterministic` `latest_status`
- `.runs[]`：全部 run 的展开记录，含 `run_id` `task_id` `agent` `model` `status` `score` `run_dir`（可拼回 run 目录）

---

## 第 1 步：定性——先分清是谁的问题（最重要的一步）

```bash
jq -r '[.run.status, .run.status_reason, (.failure_stats.invalid_submissions|tostring)] | @tsv' <run>/report/report.json
jq -r '.attempts[] | [.attempt_id, (.valid|tostring), (.score//"—"|tostring), (.failed_rule_ids|join(","))] | @tsv' <run>/report/report.json
```

| status + 特征 | 定性 | 归属 |
| --- | --- | --- |
| PASSED | 通过，仍做效率复盘（第几轮才过、维度贴线、工具浪费、成本） | 见分支 D |
| FAILED 且存在 valid=true 的有分轮次 | Agent 能力不足（提交合法但内容不行） | Agent 侧，见分支 A |
| FAILED 且全部轮次 valid=false | 接入/提交契约问题（内容根本没进判分，**不是能力问题**） | 接入侧，见分支 B |
| FAILED + reason=timeout / agent_exhausted | Agent 放弃或超时，先读 agent-logs 再归因 | Agent/接入 |
| FAILED + reason=max_attempts_reached | 用尽轮次仍不达标，看 score_trajectory 判收敛性 | Agent 侧（或接入未传反馈） |
| ERROR（CLI 退出码 2） | 评审设施故障，**不是 Agent 的错**，本 run 不计入任何成绩口径 | 框架/环境，见分支 C |
| INTEGRITY_BROKEN | hidden 在 run 期间被动过，全部结果不可信 | 按安全事件处理 |
| PENDING_HUMAN | Agent 主动请求人工复核 | 转人工 |

---

## 第 2 步：证据链（按需读取，全部只读）

| 想回答 | 读什么 | 怎么读 |
| --- | --- | --- |
| 差在哪些维度/检查 | `report/report.json` 的 `best_attempt.dimension_breakdown` / `best_attempt.failed_rules` / `failure_stats.by_rule` | `jq '.best_attempt.dimension_breakdown, .best_attempt.failed_rules, .failure_stats.by_rule'` |
| 这条检查为什么挂（最富信息源） | `judge/<attempt>.judge.json` 的 `private_notes` | `jq -r '.private_notes' judge/attempt_001.judge.json` |
| Agent 被告知了什么（合法提交） | `feedback/<attempt>.feedback.json` 的 `feedback` / `failed_checks` / `dimension_scores` | `jq '{valid, feedback, failed_checks, dimension_scores}' feedback/attempt_001.feedback.json` |
| Agent 被告知了什么（非法提交） | `feedback/<attempt>.feedback.json` 含 `valid:false` + 错误描述 | 整文件读 |
| Agent 实际交了什么 | `inbox/<attempt>.json` | 直接读 |
| Agent 过程说了什么 | `agent-logs/<attempt>.log`（仅 cli/docker 型有） | 读尾部 |
| 框架层发生了什么 | `traces/trace.jsonl` 的 error / submission_invalid 事件 | `rg '"type":"(error|submission_invalid)"' traces/trace.jsonl` |
| Agent 收到的原始题面 | `instructions.md`（run 根目录） | 直接读 |
| 判分可复现性抽查 | 离线重判 | `bin/agent-eval judge --task <taskDir> --submission <run>/inbox/<attempt>.json --trace <run>/traces/trace.jsonl` |

补充说明：
- `jq` 缺失时可读 `report/report.md` 人读版兜底
- `bin/agent-eval` 需已构建 jar（`mvn -q -DskipTests package`）
- `judge/baseline.json` 存储评分规则的初始指纹，用于完整性校验

---

## 第 3 步：按定性深挖

### A. 能力问题

1. **失分排序**：`best_attempt.dimension_breakdown` 里 `earned < max` 的维度 × `failure_stats.by_rule` 里反复挂的 rule。
2. **跨轮学习性**：`score_trajectory` + 逐轮 `failed_rule_ids` 对比：
   - **收敛型**（分数上行、失败集缩小）：Agent 能利用反馈，可适当加 `max_attempts`
   - **原地踏步**（同 rule 反复挂，如 `[60, 60, 60]`）：优先查接入——cli 模板是否传了 `AEL_FEEDBACK`、instructions 全文；再看 `feedback_fail` 文案是否可操作
   - **发散型**（越改越差）：Agent 的多轮策略有问题
3. 用 `private_notes` 把差距翻译成具体动作——`private_notes` 格式为 `RULE_ID [PASS/FAIL score/max] 详情`（如「实际=7 期望=6」「未命中: [...]」）。

### B. 契约问题

当全部轮次 `valid=false` 时逐条排查：
- `task_id` = 任务 id？
- `attempt_id` = 文件名（如 `attempt_001`）？
- `submission_type` 匹配 task.yaml 的 schema 分型？
- `summary` ≥ 8 字符？
- `known_risks:[]` / `needs_human_review:false` 是否缺失？
- `schema_version:1` 是否缺失？

若 inbox 里根本没有文件（trace 有 `reason:no_submission`）→ Agent 没把提交写到 `$AEL_INBOX/$AEL_ATTEMPT_ID.json`，检查 `--cmd` 模板与 Agent 对 `AEL_*` 环境变量的处理。

### C. 框架故障

trace `error` 事件的 `reason` 定根因：
- `judge_failure`：常见于 llm_rubric 判分模型未配置（fail-closed 是设计行为）
- `trace_integrity_broken`：签名密钥泄露或 trace 被篡改
- `no_submission`：Agent 进程正常退出但未写文件

修环境/任务配置后重跑；ERROR run 绝不混进通过率统计。

### D. PASSED 复盘

- 第几轮才过（首轮过说明题偏易或 Agent 强）
- 失败轮的 rule 分布暴露短板（最终过了≠没有弱项）
- `tool_usage.unreferenced_success_calls > 0` = 调了工具没在提交里引用（浪费或幻觉调用）
- `cost`（`report.json` 顶层）为 Agent 自报口径，含 `input_tokens` / `output_tokens` / `cost_usd`，只做 ROI 参考

### suite 层分析

**single 模式**：先读 `risk_summary`：
- `not_passed_tasks`：未通过的任务列表
- `setup_error_tasks`：基础设施出错的任务
- `flaky_tasks`：`pass_at_1=true` 而 `pass_at_k=false` 的任务
- `failed_rules_by_id`：失败规则热度图
- `action_required`：是否需要立即关注

**comparison 模式**：矩阵横向看——
- **所有 Agent 都挂的任务先怀疑任务本身**（brief 歧义 / 判分过严）
- 单个 Agent 挂才是 Agent 问题
- `status: "SETUP_ERROR"` 或 `error` 非 null 的格子是基础设施问题
- `score: null` = 无有效提交（契约问题，不是 0 分）

**可靠性口径**：`pass_at_1=true` 而 `pass_at_k=false` → flaky，建议 `--repeat 3` 定量

### history 层分析

- 通过率/最佳分走势的拐点对照 `engine_version` 与任务变更时间
- `deterministic=true` 的任务分数漂移 → 真实退步；`deterministic=false`（含 llm_rubric）的漂移是预期
- history 按 adapter name 聚合，多个 `--label` 不同的 cli Agent 会混进同一行 `agent:"cli"`

---

## 第 4 步：产出（固定结构，先说结论）

1. **TL;DR**：一句话结论 + 责任归属（Agent 能力 / 接入契约 / 任务设计 / 框架环境 / 安全）。
2. **证据表**：发现 → 证据（文件 + 字段/原文摘录）。
3. **分类建议**，每条附「如何验证改进有效」（下次跑什么命令、看什么指标）：
   - Agent 侧：prompt/模型/多轮修正策略；
   - 接入侧：`--cmd` 是否喂全 instructions、`AEL_FEEDBACK` 是否传递、超时与提交路径；
   - 任务侧：`agent_brief` 歧义、`feedback_fail` 可操作性（不泄题前提下给方向）、`max_attempts`/`pass_score` 校准——改任务走 `ael-new-task` 流程，分析岗不动任务文件；
   - 可靠性：`--repeat k`（pass^k）、`--tier` 分层、llm_rubric 维度的漂移预期。
4. 用户要落盘时写分析报告（`runs/` 不入库，安全；不要主动新建其他文档，除非用户要求落盘）：
   - 单 run 深诊 → `<run>/report/analysis.md`
   - suite 对比 → `<suite-dir>/analysis.md`（与 `suite_report.json` 同级）

---

## 安全红线（分析岗特有）

- `judge/*.judge.json` 的 `private_notes` 与 `hidden/` 全部内容只供本地诊断；**禁止**复制进任何会回喂被评 Agent 的材料（prompt、instructions、feedback、公开报告、PR 描述）——期望值泄露=题库作废。
- 对外分享的分析只引用 `rule_id`、对外 feedback 文案与维度分，不引用 `private_notes` 原文。
- 别为「复现问题」改 hidden/ 或 task.yaml 再重跑——那是出题侧变更（走 `ael-new-task`）；分析岗保持只读。

---

## 常见误读

| 现象 | 误读 | 正解 |
| --- | --- | --- |
| suite 里 `score:null` | 得了 0 分 | 无有效提交，契约问题 |
| 退出码 2 / status=ERROR | Agent 太差 | 评审设施故障，不计成绩 |
| `pass_at_1` 过、`pass_at_k` 挂 | 已达标 | flaky，可靠性不足 |
| `untrusted_trace_events > 0` | 可忽略 | 存在签名不可核验的工具事件，按可疑/伪造处理 |
| `safety.canary_leaks > 0` | 可忽略 | Agent 接触过 hidden 诱饵，按作弊线索处理 |
| auto_eval 采样分 | 正式成绩 | 过程快照，不参与成绩 |
| llm_rubric 维度复跑分数漂 | 判分坏了 | `deterministic=false` 本就非确定（权重被限 ≤30%） |
| `status_reason: max_attempts_reached` | 系统异常 | Agent 用尽轮次仍未达标，属正常流转 |
| history 里同 task 两行 agent=cli | 两个 Agent | 同一适配器多次跑（label 不同但 adapter 同名），横向对比用 suite |

---

## 自测验证（维护本 skill 时跑一遍）

用仓库内 `evalsets/demo-ops-agent/` 做端到端演练（smart 全过、naive 全挂，区分度已设计好）：

```bash
# 0. 确保 jar 存在
mvn -q -DskipTests package

# 1. 生成产物（若 runs/ 为空或想重跑）
bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks \
    --runs-root evalsets/demo-ops-agent/runs \
    --agents-file evalsets/demo-ops-agent/agents.yaml

# 2. 套件层验证：comparison 模式面板
SUITE=$(ls -t evalsets/demo-ops-agent/runs/suite/*/suite_report.json | head -1)
echo "Mode: $(jq -r '.mode' "$SUITE")"
jq -r '.agents[] | [.agent, .all_passed, (.pass_rate*100|tostring+"%")] | @tsv' "$SUITE"
# 预期：baseline-scripted 2/2, smart-ops 2/2, naive-ops 0/2

# 3. 下钻 naive log-triage：定性为 Agent 能力 + 原地踏步
RUN_ID=$(jq -r '.agents[]|select(.agent=="naive-ops")|.results[]|select(.task_id=="log-triage-001")|.runs[0].run_id' "$SUITE")
RUN_DIR="evalsets/demo-ops-agent/runs/log-triage-001/$RUN_ID"
jq '{status: .run.status, reason: .run.status_reason, trajectory: .score_trajectory,
     attempts: [.attempts[]|{id:.attempt_id, valid, score, rules:.failed_rule_ids}]}' \
    "$RUN_DIR/report/report.json"
# 预期：status=FAILED, reason=max_attempts_reached, trajectory=[60,60,60]
#        每轮 failed_rule_ids=["ERROR_COUNT_CORRECT"]

# 4. private_notes 深诊
jq -r '.private_notes' "$RUN_DIR/judge/attempt_001.judge.json"
# 预期输出含："ERROR_COUNT_CORRECT [FAIL 0.0/40.0] 不相等: 实际=7 期望=6"

# 5. 判分可复现抽查
bin/agent-eval judge --task evalsets/demo-ops-agent/tasks/log-triage-001 \
    --submission "$RUN_DIR/inbox/attempt_001.json" \
    --trace "$RUN_DIR/traces/trace.jsonl"
# 预期：score=60, 与 judge json 一致

# 6. 单 Agent pass^k 可靠性
bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks \
    --runs-root evalsets/demo-ops-agent/runs \
    --agent cli --cmd "bash $PWD/evalsets/demo-ops-agent/agents/smart_ops_agent.sh" \
    --label smart-ops --repeat 2
SINGLE=$(ls -t evalsets/demo-ops-agent/runs/suite/*/suite_report.json | head -1)
jq '.risk_summary' "$SINGLE"
# 预期：mode=single, risk_summary.action_required=false, all_passed=true

# 7. 趋势层
bin/agent-eval history --runs-root evalsets/demo-ops-agent/runs
```

### 预期分析结论

与 `evalsets/demo-ops-agent/README.md` 一致：
- naive 日志任务 60 分：ERROR_COUNT 实际 7 vs 期望 6（grep 陷阱——INFO 行消息含 "error" 被误数）
- naive 审计任务 15 分：漏报违规项、most_critical 错
- 三轮 `score_trajectory` 平坦 `[60, 60, 60]` → 不读 `AEL_FEEDBACK` 或策略不收敛
- 分析输出应能指出具体 `rule_id` 与 `private_notes` 里的实际/期望值差
- **不得**把期望值写进给 Agent 的优化建议（安全红线）
