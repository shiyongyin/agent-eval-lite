---
name: ael-analyze-results
description: 测评完成后 AI 辅助解读评估产物（run/suite/history），定位问题归属并给出可验证的优化建议。当用户说"分析测评结果"、"为什么没通过/掉分"、"解读评估报告"、"Agent 差在哪、怎么改进"时使用。
---

# 测评结果分析与优化建议

你（AI）在这个工作流里是「评估分析师」：只读诊断既有产物，不重跑评估、不改任何工件。每条结论都必须挂证据（文件+字段）；没有证据支撑的判断要明说是假设。字段语义权威：根 `README.md`（报告与命令）、`docs/CODEMAP.md`（report/state 包）。

**与相邻 skill 的分工**：`ael-build-evalset` 负责建集与实跑；本 skill 只在已有 `runs/` 产物上解读。用户还没跑过评估 → 先走 build-evalset；跑完要读报告 → 走本 skill。

## 第 0 步：定位输入形态

| 用户给的 | 主视图 | 粒度 |
| --- | --- | --- |
| 单次 run 目录 `<runs-root>/<task-id>/run_*` | `report/report.json`（`report.md` 是人读版） | 单任务单 Agent 深诊 |
| 套件/对比目录 `<runs-root>/suite/*` | `suite_report.json`（单 Agent 模式先看 `risk_summary`） | 多任务/多 Agent 横向 |
| 趋势诉求（"最近怎么样"） | `bin/agent-eval history --runs-root <runs>`（只读聚合，可放心重跑） | 跨 run/Agent/版本走势 |

suite/history 锁定可疑点后，一律经 `run_id` 下钻到单 run 目录做深诊（suite_report.json 的 `runs[]` 数组带 `run_id`；run 目录名即 run_id）。

**快速定位最新产物**（用户只给 `--runs-root` 时）：

```bash
# 最新 suite 对比报告
ls -t <runs-root>/suite/*/suite_report.json | head -1

# 某任务最新 run
ls -t <runs-root>/<task-id>/run_* | head -1

# suite 面板摘要（谁挂了、几分；单 Agent 模式可先看 risk_summary）
jq -r '.agents[] | [.agent, .all_passed, (.pass_rate*100|tostring+"%"), .total] | @tsv' "$(ls -t <runs-root>/suite/*/suite_report.json | head -1)"
jq '.risk_summary // empty' "$(ls -t <runs-root>/suite/*/suite_report.json | head -1)"
```

**history 与 suite 的口径差**：`history` 按 `(task_id, report.run.agent)` 聚合（如多个 cli Agent 都叫 `cli`）；**多 Agent 横向对比必须以 suite_report 的 `agent` 标签（`--label`）为准**，history 只看单 Agent 纵向趋势。

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
| ERROR（CLI 退出码 2） | 评审设施故障，**不是 Agent 的错**，本 run 不计入任何成绩口径 | 框架/环境，见分支 C |
| INTEGRITY_BROKEN | hidden 在 run 期间被动过，全部结果不可信 | 按安全事件处理 |
| PENDING_HUMAN | Agent 主动请求人工复核 | 转人工 |

## 第 2 步：证据链（按问题读，全部只读）

| 想回答 | 读 | 怎么读 |
| --- | --- | --- |
| 差在哪些维度/检查 | report.json 的 `best_attempt.dimension_breakdown` / `failed_rules`、`failure_stats.by_rule` | `jq '.best_attempt'` |
| 这条检查为什么挂（最富信息源） | `judge/<attempt>.judge.json` 的 `private_notes`（含真实运行报错、未命中关键点组、缺失 jsonpath） | `jq -r '.private_notes'` |
| Agent 被告知了什么 | `feedback/<attempt>.feedback.json`：合法提交看 `feedback`/`failed_checks`；契约违规看 `schema_errors`（第一现场） | `jq '{valid, feedback, failed_checks, schema_errors}'` |
| Agent 实际交了什么 | `inbox/<attempt>.json` | 直接读 |
| Agent 过程说了什么 | `agent-logs/<attempt>.log`（cli/docker 型才有；scripted/http 无） | 读尾部 |
| 框架层发生了什么 | `traces/trace.jsonl` 的 `error` / `submission_invalid` 事件 | `rg '"type":"(error\|submission_invalid)"' traces/trace.jsonl` |
| Agent 收到的原始题面 | `<run>/instructions.md` | 直接读 |
| 判分可复现性抽查 | 离线重判，分数必须与 judge json 一致 | `bin/agent-eval judge --task <taskDir> --submission <run>/inbox/<attempt>.json --trace <run>/traces/trace.jsonl` |

（jq 缺失时可读 `report/report.md` 人读版兜底；`bin/agent-eval` 需已构建 jar。）

## 第 3 步：按定性深挖

**A. 能力问题**：
1. 失分排序：`dimension_breakdown` 里 earned<max 的维度 × `failure_stats.by_rule` 里反复挂的 rule。
2. 跨轮学习性：`score_trajectory` + 逐轮 `failed_rule_ids` 对比——收到反馈后失败集是否缩小？**收敛型**（上行）说明 Agent 能利用反馈，可适当加 `max_attempts`；**原地踏步**（同一批 rule 反复挂）优先查接入（cli 模板是否传了 `AEL_FEEDBACK`/instructions 全文）与 `feedback_fail` 文案是否可操作；**发散型**（越改越差）指向 Agent 的多轮策略问题。
3. 用 `private_notes` 把差距翻译成具体动作（如 SPEC_BEHAVIOR 的真实异常栈、list_coverage 未命中的关键点组）。

**B. 契约问题**：`feedback` 的 `schema_errors` 逐条对照速查——`task_id`=任务 id？`attempt_id`=文件名？`submission_type` 匹配任务分型？`summary`≥8 字符？`known_risks`/`needs_human_review` 是否缺失？若 inbox 里根本没有文件（trace 有 `reason:no_submission`）→ Agent 没把提交写到 `$AEL_INBOX/$AEL_ATTEMPT_ID.json`，检查 `--cmd` 模板与 Agent 对 `AEL_*` 环境变量的处理。

**C. 框架故障**：trace `error` 事件的 `reason`（`judge_failure` / `trace_integrity_broken` / `no_submission`…）定根因；`judge_failure` 常见于 llm_rubric 判分模型未配置（fail-closed 是设计行为）。修环境/任务配置后重跑；ERROR run 绝不混进通过率统计。

**D. PASSED 复盘**：第几轮才过（首轮过说明题偏易或 Agent 强）；失败轮的 rule 分布暴露短板（最终过了≠没有弱项）；`tool_usage.unreferenced_success_calls`>0 = 调了工具没在提交里引用（浪费或幻觉调用）；`cost` 为 Agent 自报口径，只做 ROI 参考。

**suite/对比层**：单 Agent 先读 `risk_summary.not_passed_tasks`、`setup_error_tasks`、`flaky_tasks`、`failed_rules_by_id`；`pass_at_1`=true 而 `pass_at_k`=false → flaky，建议 `--repeat 3` 定量；`score:null` = 无有效提交（契约问题，不是 0 分）；矩阵横向看——**所有 Agent 都挂的任务先怀疑任务本身**（brief 歧义/判分过严），单个 Agent 挂才是 Agent 问题；`status:SETUP_ERROR/ERROR` 的格子是基础设施问题。
**history 层**：通过率/最佳分走势的拐点对照 `engine_version` 与任务变更时间；`判分=非确定`的行（含 llm_rubric）分数漂移是预期；红队门禁摘要非 pass 时一切结论都要打折。

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

## 安全红线（分析岗特有）

- `judge/*.judge.json` 的 `private_notes` 与 `hidden/` 全部内容只供本地诊断；**禁止**复制进任何会回喂被评 Agent 的材料（prompt、instructions、feedback、公开报告、PR 描述）——期望值泄露=题库作废。
- 对外分享的分析只引用 `rule_id`、对外 feedback 文案与维度分，不引用 `private_notes` 原文。
- 别为「复现问题」改 hidden/ 或 task.yaml 再重跑——那是出题侧变更（走 `ael-new-task`）；分析岗保持只读。

## 常见误读

| 现象 | 误读 | 正解 |
| --- | --- | --- |
| suite 里 `score:null` | 得了 0 分 | 无有效提交，契约问题 |
| 退出码 2 / status=ERROR | Agent 太差 | 评审设施故障，不计成绩 |
| `pass_at_1` 过、`pass_at_k` 挂 | 已达标 | flaky，可靠性不足 |
| `untrusted_trace_events`>0 | 可忽略 | 存在签名不可核验的工具事件，按可疑/伪造处理 |
| `safety.canary_leaks`>0 | 可忽略 | Agent 接触过 hidden 诱饵，按作弊线索处理 |
| auto_eval 采样分 | 正式成绩 | 过程快照，不参与成绩 |
| llm_rubric 维度复跑分数漂 | 判分坏了 | `deterministic=false` 本就非确定（权重被限 ≤30%） |

## 自测验证（维护本 skill 时跑一遍）

用仓库内 `evalsets/demo-ops-agent/` 做端到端演练（smart 全过、naive 全挂，区分度已设计好）：

```bash
# 1. 生成产物（若 runs/ 为空）
bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks \
    --runs-root evalsets/demo-ops-agent/runs \
    --agents-file evalsets/demo-ops-agent/agents.yaml

# 2. 套件层：naive-ops 0/2，smart-ops 2/2
SUITE=$(ls -t evalsets/demo-ops-agent/runs/suite/*/suite_report.json | head -1)
jq -r '.agents[] | [.agent, .all_passed, (.pass_rate*100|tostring+"%")] | @tsv' "$SUITE"

# 3. 下钻 naive log-triage：应定性为 Agent 能力 + 原地踏步（三轮同分同 rule）
RUN=$(jq -r '.agents[]|select(.agent=="naive-ops")|.results[]|select(.task_id=="log-triage-001")|.runs[0].run_id' "$SUITE")
jq '{status: .run.status, trajectory: .score_trajectory, attempts: [.attempts[]|{id:.attempt_id, score, rules:.failed_rule_ids}]}' \
    "evalsets/demo-ops-agent/runs/log-triage-001/$RUN/report/report.json"

# 4. 判分可复现抽查
bin/agent-eval judge --task evalsets/demo-ops-agent/tasks/log-triage-001 \
    --submission "evalsets/demo-ops-agent/runs/log-triage-001/$RUN/inbox/attempt_001.json" \
    --trace "evalsets/demo-ops-agent/runs/log-triage-001/$RUN/traces/trace.jsonl"

# 5. 趋势层
bin/agent-eval history --runs-root evalsets/demo-ops-agent/runs
```

预期结论（与 `evalsets/demo-ops-agent/README.md` 一致）：naive 日志任务 60 分（ERROR_COUNT：7 vs 6，grep 陷阱）；审计任务 15 分（漏 SEC-4、most_critical 错）；三轮 `score_trajectory` 平坦 → 不读 `AEL_FEEDBACK` 或策略不收敛。分析输出应能指出具体 rule_id 与 `private_notes` 里的实际/期望值差，且**不得**把期望值写进给 Agent 的优化建议。
