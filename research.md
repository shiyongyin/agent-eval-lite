# 参考外部框架校准 AgentEval-Lite 的加固清单

> 日期：2026-07-09
> 目标：不是做更多功能，而是参考 AgentLoop、Inspect AI、promptfoo、Phoenix/Langfuse、garak/PyRIT、tau-bench/SWE-bench 等外部框架的成熟做法，检查 AgentEval-Lite 已有能力是否足够扎实。

## 1. 结论

当前重点不应该是继续扩功能，而是把已经有的评测能力做成“可证明、可回归、可解释、可防作弊”的工程事实。

AgentEval-Lite 已经覆盖了本地 Agent 评测内核的关键面：任务目录契约、Work/Judge 隔离、结构化提交、规则 judge、低权重 LLM judge、HMAC trace、工具网关、world_state 终态比对、Docker sandbox、suite 横评、history、OTLP/OpenInference 导出、红队门禁和 CI smoke。

真正需要查缺补漏的是这些能力的“扎实度”：

- 每个能力是否有正向样例、反向样例、边界样例。
- 每个安全声明是否有红队用例锁住。
- 每个报告字段是否能从 run 工件重建，而不是口头描述。
- 每个外部非确定性能力是否 fail-closed，并如实标注不可复现。
- 每个横评结论是否能被 repeat/pass^k、历史趋势或失败热点支撑。
- 每个“已支持”的功能是否进入 CI 或最小验证闸。

因此后续工作应写成“加固项”，而不是“新增功能项”。

## 2. 外部框架给我们的校准标准

| 外部参考 | 值得借鉴的标准 | 对 AgentEval-Lite 的含义 |
| --- | --- | --- |
| AgentLoop | 运行数据、评测、实验和资产迭代要形成闭环 | 不追云平台，但 suite/report/history 要能解释“为什么这个 Agent 版本变好/变差” |
| Inspect AI | Task/Solver/Scorer 分离、工具调用可由 scorer 核验、sandbox 是一等能力 | 现有 runner/adapter/judge 分层要保持清楚；tool_call/world_state 必须只认可信 trace |
| promptfoo | 断言模型清晰，CI 退出码明确，redteam 可脚本化 | 每个 check 都要有清楚的失败语义、退出码和负例 |
| Phoenix/Langfuse | trace 是跨 run 诊断和成本分析的事实基础 | AEL 自研 trace 是判分事实源，导出只做观察视图；不能让外部看板反过来成为事实源 |
| garak/PyRIT | 红队要有 probe/detector 思维和回归门禁 | AEL 红队重点不是模型说坏话，而是 hidden exfil、越权工具、伪造 trace、judge 注入 |
| tau-bench | 只看话术不够，要看最终世界状态和 pass^k 稳定性 | `world_state` 和 `repeat` 是核心能力，应继续补边界反例 |
| SWE-bench | 既要 FAIL_TO_PASS，也要 PASS_TO_PASS，防 reward hacking | 代码修复类任务必须锁住“不破坏既有行为” |

## 3. 当前已有能力的扎实度矩阵

状态说明：

- **扎实**：已有实现、正反测试、报告证据，并进入 CI/红队/验证闸。
- **基本扎实**：已有实现和测试，但证据链或文档边界还可补。
- **需加固**：已有能力，但缺反例、缺门禁、缺失败解释或容易被误用。

| 能力域 | 当前状态 | 已有证据 | 主要缺口 | 加固动作 |
| --- | --- | --- | --- | --- |
| 任务目录契约 | 扎实 | `tasks/*`、`task init`、`validate`、`TasksValidationTest`、`TaskInitScaffoldTest` | 任务质量更多靠规范阅读 | 把任务质量清单里的关键项尽量固化到 validate lint |
| Work/Judge 隔离 | 基本扎实 | `hidden/` 契约、workspace 副本、Docker sandbox、红队 A/A-sym/A-find | 非 Docker 模式仍是约定+审计 | 文档和报告中继续明确：不可信 Agent 必须 Docker |
| 结构化提交 | 扎实 | envelope schema、`SubmissionManagerTest`、红队 B 系列非法提交 | 可继续增加 schema 边界样例 | 对 oversized、extra fields、wrong task/attempt 保持红队覆盖 |
| 规则 judge | 扎实 | `RulesJudgeTest`、`RulesJudgeHardeningTest`、`JudgeRunnerTest` | check 越多越容易反馈泄露 | 每新增 check 必须同步验证 feedback 不泄露 expected |
| 脚本 judge | 基本扎实 | `ScriptJudgeTest`、hybrid 设计 | 外部脚本错误的诊断口径可更清楚 | 补脚本超时、非零退出、非法 JSON 的报告样例 |
| LLM judge | 基本扎实 | `LlmRubricJudgeTest`、`LlmRubricRedTeamTest`、`ValidateLintTest` | 需要持续防“LLM 主判化”误用 | 保持禁止 blocking、权重上限、fail-closed、原始请求响应存档 |
| HMAC trace | 扎实 | `TraceSignerTest`、`TraceLoggerTest`、红队 C 伪造/删除 trace | trace 事件类型扩展时易漏 schema | 新事件必须同步 schema、签名测试、导出测试 |
| 工具网关 | 扎实 | allowlist、mock replay、live 录制、`ToolBackendLiveReplayTest`、红队 G/H | live 模式可能被误解成默认外呼 | README/报告中强调默认 replay，live 仅录制可晋升样本 |
| world_state 终态比对 | 扎实 | `RulesJudgeWorldStateTest`、红队 I、`tool-call-001` 多轮回放 | 多工具、多次写入语义需要任务作者理解 | 在规则文档中明确 scope、order_sensitive、多重集语义 |
| Docker sandbox | 基本扎实 | `DockerSandboxTest`、`DockerSandboxRunTest`、红队 Docker 路径 | Docker 强度依赖宿主配置和镜像可信度 | 把宿主 Docker 前提写进安全边界，不承诺强于 Docker 本身 |
| suite 横评 | 扎实 | `SuiteRunnerTest`、`SuiteCommandAgentsFileTest`、`suite_report` | 横评结论依赖任务集质量 | report 中持续暴露 flaky、失败规则热点和 repeat 稳定性 |
| history 趋势 | 基本扎实 | `HistoryCommandTest`、`history` CLI | 只是汇总 run 文件，长期索引能力有限 | 先不做新存储；补“历史报告如何解读”的文档和样例 |
| usage 成本 | 基本扎实 | `UsageReportingTest`、suite 聚合测试 | 成本由 Agent 自报，不防谎报 | 报告始终标注“自报，不参与评分” |
| OTLP/OpenInference 导出 | 基本扎实 | `OtlpTraceExporterTest`、`export` CLI | 外部看板不能作为判分事实源 | 文档强调 JSONL/HMAC trace 是事实源，导出是观察副本 |
| 红队门禁 | 扎实 | `redteam/run_all.sh`、`redteam/test_gate.sh`、`bin/ci-smoke.sh` | 新能力如果不进红队，会形成安全盲区 | 新增安全相关能力必须补 redteam case 或说明不适用 |
| CI smoke | 扎实 | `bin/ci-smoke.sh` 四道闸 | 运行成本较高时可能被跳过 | 保持文档-only 最小闸和代码改动完整闸分层 |

## 4. 需要优先补的不是功能，而是证据

### 4.1 把“已支持”变成“可验证”

每个已有功能都应满足这五个条件：

| 条件 | 说明 | 当前最该补的位置 |
| --- | --- | --- |
| 正例 | 合法输入能通过，并能在报告中看到结果 | 新 check、新任务类型、新 adapter |
| 反例 | 典型作弊/错误输入会失败 | judge、tool、trace、Docker、LLM judge |
| 边界例 | 空值、缺字段、超时、非法 JSON、重复调用等边界明确 | script judge、tool backend、history/export |
| 报告证据 | `report.json/.md` 或 `suite_report` 能解释结论 | suite、history、usage、LLM judge |
| 门禁归属 | 知道该跑 `validate`、单测、suite、redteam 还是 ci-smoke | README、PLAYBOOK、ael-verify |

### 4.2 把“能力强”变成“边界诚实”

外部平台常见问题是把 observability、LLM judge、dataset 管理包装得很强，但真实评测时边界不清。AEL 的差异化正好相反：宁可窄，也要边界硬。

需要继续保持这些表述：

- `llm_rubric` 是低权重主观信号，不是主判。
- `usage` 是 Agent 自报成本，不参与评分，不防 Agent 谎报。
- OTLP/OpenInference 导出是观察副本，不是判分事实源。
- 非 Docker 模式不承诺防住任意 shell 的恶意 Agent。
- live 工具是录制入口，不是 CI 默认路径。
- hidden 规则、expected、mock 响应库不能泄露到公开材料。

## 5. 按外部框架反推的查缺补漏清单

### 5.1 对照 AgentLoop：把报告解释做扎实

AgentLoop 的启发不是“做平台”，而是评测结果要能回答运营问题：哪个 Agent 版本退化、退化在哪类任务、失败原因是什么。

现有基础：

- `suite_report.json/.md` 已有任务矩阵、稳定通过数、失败规则热点、平均耗时和可选成本聚合。
- `history` 已能汇总 runs。
- `ael-analyze-results` skill 负责基于报告、trace、feedback 诊断问题。

加固项：

- suite 报告中的每个排名结论都应能追溯到任务级 run。
- history 报告要明确样本范围、过滤条件、是否包含 redteam 摘要。
- 失败规则热点要保持“对外反馈文案”口径，不能泄露 hidden expected。

验收：

- `SuiteRunnerTest` 保持覆盖矩阵、repeat、不稳定 Agent、usage 聚合。
- `HistoryCommandTest` 覆盖过滤、趋势、redteam 摘要。
- 文档示例里只展示公开报告，不展示 hidden judge 明细。

### 5.2 对照 Inspect AI：把 scorer/tool/sandbox 边界做扎实

Inspect AI 的强项是 Solver/Scorer 分离、工具循环和 sandbox。AEL 对应的是 Adapter/Judge 分离、ToolGateway 和 DockerAgentAdapter。

现有基础：

- AgentAdapter 支持 manual/scripted/cli/http。
- Judge 只读 submission、workspace、hidden、trace。
- tool_call_required 和 world_state 只认可签名 trace。
- Docker 只挂载 workspace/inbox/feedback/instructions。

加固项：

- 任何 judge 都不能依赖 Agent stdout 的自然语言。
- tool_call 相关评分必须走 trace call_id，不读 Agent 自述。
- Docker 路径保持红队 A/A-sym/A-find 覆盖。
- http adapter 的不可达、非 2xx、非法响应要按基础设施故障或 invalid 明确区分。

验收：

- `SubmissionManagerTest`、`RulesJudgeWorldStateTest`、`DockerSandboxRunTest`、`HttpAgentRunTest` 必须覆盖主路径和失败路径。
- 红队 C/G/I 必须持续通过。

### 5.3 对照 promptfoo：把断言和退出码做扎实

promptfoo 的价值在于断言简单、失败明确、CI 好接。AEL 的 rules judge 也要保持这个风格。

现有基础：

- `validate` 静态体检。
- `suite --fail-on-not-passed` 可作为门禁。
- `bin/ci-smoke.sh` 串起 CODEMAP、测试、validate、suite、redteam。

加固项：

- 每个 check 的失败都要有稳定 rule id 和外部 feedback。
- validate 要尽量早发现规则引用错误、权重错误、schema 文件缺失、工具白名单引用错误。
- CLI 退出码语义不要随意变化。

验收：

- `ValidateLintTest` 继续覆盖规则 lint。
- suite 失败退出码和 redteam 失败退出码保留负向验证。
- 新 check 必须补“配置错误”和“判分失败”两个层面的测试。

### 5.4 对照 Phoenix/Langfuse：把 trace 事实源做扎实

Phoenix/Langfuse 是观察平台，不是裁判。AEL 必须保持自研 HMAC trace 作为判分事实源。

现有基础：

- Trace JSONL 事件有 schema。
- 每 run HMAC 签名。
- export 输出 OTLP/OpenInference。
- report 可从 run 工件重建。

加固项：

- 新 trace 事件必须同步 schema、签名、导出。
- 导出 span id 保持确定性，重复导出不分叉。
- report 不应依赖外部看板回写的数据。

验收：

- `TraceSignerTest`、`TraceLoggerTest`、`OtlpTraceExporterTest` 保持覆盖。
- `git diff --check` 之外，trace/report 改动必须跑相关单测。

### 5.5 对照 garak/PyRIT：把红队当回归资产

garak/PyRIT 的启发是 probe/detector 分离和攻击语料沉淀。AEL 不需要复制模型红队平台，但要把系统级攻击锁住。

现有基础：

- A hidden 偷看。
- B 非法提交。
- C trace 删除/伪造。
- D command reward hacking / PASS_TO_PASS。
- E judge 注入。
- G 越权工具和工具入参 exfil。
- I 错误终态。
- J LLM judge fail-closed / 禁 blocking / 权重限制。

加固项：

- 每个安全声明都要对应 redteam case。
- redteam case 要有 DEFENDED/VULNERABLE/INFRA/CHECK 清晰分类。
- 登记基线不能无声扩大。

验收：

- `redteam/test_gate.sh` 先证明门禁逻辑自身 fail-closed。
- `redteam/run_all.sh` 进入 `bin/ci-smoke.sh`。
- 新增安全相关能力时，同步补 redteam 或写明为什么不适用。

### 5.6 对照 tau-bench/SWE-bench：把“做对”定义做扎实

tau-bench 和 SWE-bench 的共同提醒是：最终状态和回归行为比漂亮回答更重要。

现有基础：

- `world_state` 比对实际工具写入后的终态。
- `repeat` 用 pass^k 衡量稳定性。
- code-fix 任务有 FAIL_TO_PASS / PASS_TO_PASS 思路。
- redteam D2 防止“修一个点破坏旧行为”。

加固项：

- 涉及真实状态变化的任务优先使用 world_state，而不是只看 submission 字段。
- 代码修复任务要区分“新增通过”和“旧行为不退化”。
- suite 报告继续展示 repeat 稳定性，不只展示单次最高分。

验收：

- `RulesJudgeWorldStateTest` 覆盖重复写入、顺序敏感、attempt/run scope、伪造事件不计入。
- `SuiteRunnerTest` 覆盖不稳定 Agent 被 pass^k 抓出。
- 代码任务样例保留 PASS_TO_PASS 反例。

## 6. 最小加固路线

这里的“路线”不是新功能路线，而是把已有能力补证据、补边界、补门禁。

### 第一优先级：安全和可信链

| 加固项 | 动作 | 验收 |
| --- | --- | --- |
| hidden 防泄露 | 检查任务文档、samples、feedback 是否可能泄露 expected 或 judge 规则 | `ael-review-task-quality` + redteam A/H |
| trace 防伪 | 新 trace 事件必须 schema + HMAC + 导出测试 | `TraceSignerTest`、`OtlpTraceExporterTest` |
| tool_call 可信核验 | 评分只认 trace call_id，不认 submission 自述 | `RulesJudgeWorldStateTest` + redteam G/I |
| LLM judge 防误用 | 禁 blocking、权重上限、fail-closed、存档 | `ValidateLintTest` + `LlmRubricJudgeTest` + redteam J |
| Docker 边界 | 不可信 Agent 文档默认引导 Docker，并说明宿主限制 | `DockerSandboxRunTest` + redteam Docker 路径 |

### 第二优先级：可复现和可解释

| 加固项 | 动作 | 验收 |
| --- | --- | --- |
| report 可重建 | 报告字段都来自 run 工件，不从外部看板回填 | `ReportGeneratorTest` |
| suite 结论可解释 | 排名、稳定通过数、失败热点可追溯到 run | `SuiteRunnerTest` |
| history 口径清楚 | 过滤条件、redteam 摘要、趋势样本范围明确 | `HistoryCommandTest` |
| usage 口径诚实 | 始终标注自报，不参与评分 | `UsageReportingTest` |
| live/replay 边界 | live 只录制，CI 默认 replay | `ToolBackendLiveReplayTest` |

### 第三优先级：任务质量

| 加固项 | 动作 | 验收 |
| --- | --- | --- |
| 任务有正反样例 | 每个任务至少 pass/fail/replay 闭环 | `validate` + scripted run |
| 任务不泄露 hidden | expected、judge 规则、mock 响应不出公开区 | `ael-review-task-quality` |
| 任务防 reward hacking | 代码类任务补 PASS_TO_PASS；工具类任务补 world_state | 对应任务回放 + redteam |
| 反馈不过度 | feedback 只给外部文案，不给 expected | `FeedbackPolicyTest` |

## 7. 现在不应投入的方向

这些方向不是错，而是不符合“把已有能力做扎实”的当前目标：

- 不做云上多租户 SaaS。
- 不做完整 Prompt/Skill/Memory 资产库。
- 不自建复杂 Trace Dashboard。
- 不把 LLM judge 升级成主判。
- 不把真实生产流量直接当 hidden truth。
- 不为了对标 AgentLoop 增加平台化工作流。

## 8. 最终判断

AgentEval-Lite 当前最有价值的不是“功能更多”，而是“评测结论更可信”。参考外部框架后，下一步应把已有功能逐项压实：

- 像 promptfoo 一样，让断言失败和 CI 退出码简单可靠。
- 像 Inspect AI 一样，让 scorer/tool/sandbox 边界清楚。
- 像 Phoenix/Langfuse 一样，让 trace 能解释问题，但不让看板替代事实源。
- 像 garak/PyRIT 一样，把攻击用例沉淀成回归资产。
- 像 tau-bench/SWE-bench 一样，把最终状态、稳定性和不退化作为“做对”的证据。

如果后续要开工，建议从这份报告里的“最小加固路线”挑一项做，不新增大功能，先补测试、红队、文档边界和验证闸。
