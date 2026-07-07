# CODEMAP（自动生成，勿手改）

> 本文件由 `bin/gen-codemap.sh` 从源码自动生成：类职责取自类级 Javadoc 首句，
> CLI 命令面取自 picocli `@Command` 注解，领域清单取自对应枚举/常量，任务库取自 `tasks/*/task.yaml`。
> 更新方式：改代码（或 Javadoc）后执行 `bash bin/gen-codemap.sh`；CI 以 `--check` 校验漂移。
> 阅读入口与分区指南见根 `AGENTS.md`；扩展点接线表见 `src/main/java/com/agenteval/AGENTS.md`。

## CLI 命令面

| 命令 | 说明 | 源文件 |
| --- | --- | --- |
| `agent-eval` | 企业内部 AI Agent 测试脚手架（AgentEval-Lite） | `com/agenteval/cli/Main.java` |
| `evalset` | 私有测评集工程化辅助 | `com/agenteval/cli/EvalsetCommand.java` |
| `evalset init` | 生成私有测评集骨架（tasks/、agents.yaml、接入脚本与落地说明） | `com/agenteval/cli/EvalsetCommand.java` |
| `export` | 导出 run 的 trace 为 OTLP/OpenInference JSON（可选直接 POST 到看板） | `com/agenteval/cli/ExportCommand.java` |
| `history` | 汇总 runs/ 历次评估产出跨 run / Agent / 版本的趋势报告（只读离线聚合） | `com/agenteval/cli/HistoryCommand.java` |
| `judge` | 对一份提交离线判分（可复现复核） | `com/agenteval/cli/JudgeCommand.java` |
| `list` | 列出任务库中的任务 | `com/agenteval/cli/ListCommand.java` |
| `report` | 重建指定 run 的评估报告 | `com/agenteval/cli/ReportCommand.java` |
| `run` | 执行（或恢复）一次评估 run | `com/agenteval/cli/RunCommand.java` |
| `suite` | 批跑任务库并生成套件汇总 / 多 Agent 对比报告（CI 冒烟门禁、pass^k 可靠性） | `com/agenteval/cli/SuiteCommand.java` |
| `task` | 任务库工程化辅助 | `com/agenteval/cli/TaskCommand.java` |
| `task init` | 生成新任务脚手架（开箱即过 validate 与回放闭环） | `com/agenteval/cli/TaskCommand.java` |
| `tool` | 工具网关 | `com/agenteval/cli/ToolCommand.java` |
| `tool call` | 经网关调用一个工具 | `com/agenteval/cli/ToolCommand.java` |
| `validate` | 校验任务定义是否合法完整 | `com/agenteval/cli/ValidateCommand.java` |

## 生产代码类索引（src/main/java）

### com.agenteval

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `Version` | 框架版本常量：写入 judge 结果的 reproducibility.engine_version，保证任何历史评分都能追溯到当时的引擎版本。 |

### com.agenteval.agent

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `AgentAdapter` | Agent 适配器 SPI：把「任何能产出提交文件的东西」接入评估循环。 |
| `AgentProcess` | 子进程型 Agent 适配器的共享执行内核：启动进程、合流落盘、按预算超时强杀。 |
| `AttemptInput` | 一次 attempt 的输入：Runner 交给 Agent 适配器的全部信息。 |
| `AttemptOutcome` | 一次 attempt 的执行结果（Agent 侧视角，评分之前）。 |
| `CliAgentAdapter` | CLI Agent 适配器：驱动任何命令行形态的编码 Agent（claude、cursor-agent、自研 CLI 等），进程直跑在宿主上。 |
| `DockerAgentAdapter` | Docker 沙箱 CLI Agent 适配器：把 CliAgentAdapter 的命令包进只挂 Agent 可触碰区的容器。 |
| `DockerAvailability` | Docker 可用性预检：在启用 --sandbox docker 前确认 docker CLI 存在且 daemon 在跑。 |
| `DockerSandbox` | Docker 沙箱配置：把「CLI Agent 的一条命令」包进 docker run --rm 的只挂 Agent 可触碰区的容器。 |
| `HttpAgentAdapter` | HTTP Agent 适配器：评估「服务形态」的 Agent（chat API、Agent 平台的 HTTP 入口、自研服务等）。 |
| `ManualAgentAdapter` | 人肉适配器：把「人」当成被评 Agent，用于调任务与演示。 |
| `ScriptedAgentAdapter` | 脚本回放适配器：按 replay.yaml 逐轮模拟一个 Agent 的行为，是框架自测与 CI 回归的确定性驱动器（不依赖任何真实模型）。 |

### com.agenteval.cli

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `EvalsetCommand` | agent-eval evalset：私有测评集工程化辅助（当前提供 init 脚手架）。 |
| `ExportCommand` | agent-eval export：把 run 的 trace.jsonl 导出为 OTLP/OpenInference JSON，供 Arize Phoenix / Langfuse / 任意 OTel Collector 摄取（外接看板，不自建可视化）。 |
| `HistoryCommand` | agent-eval history：汇总 runs/ 下历次评估的 report.json，产出「跨 run / 跨 Agent / 跨版本」的趋势报告（JSON + Markdown）。 |
| `JudgeCommand` | agent-eval judge：离线判分——不跑 Agent，直接对一份提交复算分数。 |
| `ListCommand` | agent-eval list：列出任务库中的全部任务及其概要。 |
| `Main` | CLI 根命令：agent-eval。 |
| `ReportCommand` | agent-eval report：从 run 目录的既有工件重建报告（纯读、幂等）。 |
| `RunCommand` | agent-eval run：执行（或恢复）一次评估。 |
| `SandboxSupport` | 沙箱选项的共享构建逻辑：run 与 suite 用同一口径解析 --sandbox docker 相关参数。 |
| `SuiteCommand` | agent-eval suite：用指定 Agent 批跑任务库并产出套件汇总 / 多 Agent 对比报告。 |
| `TaskCommand` | agent-eval task：任务库工程化辅助（当前提供 init 脚手架）。 |
| `ToolCommand` | agent-eval tool call：Agent 调用工具的唯一合法通道（allowlist + mock + 留痕）。 |
| `ValidateCommand` | agent-eval validate：任务规格静态体检（结构 + 引用 + 规则文件 + 规则深度 lint），供任务作者在提交任务前自查，也作为 CI 的任务库门禁。 |

### com.agenteval.judge

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `CheckOutcome` | 单个检查项的执行结论——rules 引擎与 script judge 的公共货币，由 JudgeRunner 统一聚合计分。 |
| `FeedbackPolicy` | 受控反馈策略：完整评分结果 → 按任务配置裁剪出回传 Agent 的版本。 |
| `JudgeException` | 评审执行异常：规则文件非法、脚本超时/输出不合契约等评审侧故障。 |
| `JudgeInput` | Judge 的全部输入——只读视图。 |
| `JudgeResult` | 评审输出（完整私有版）：结构化评分 + 可复现指纹。 |
| `JudgeRunner` | 评审编排器：按任务的 judge.type 分派到规则引擎 / 脚本评审，统一聚合计分。 |
| `LlmRubricJudge` | llm_rubric 检查执行器（Phase 3，设计 §5.6）：让判分模型按隐藏 rubric 给主观维度打分。 |
| `RulesFile` | 隐藏评审规则文件（hidden/judge.rules.yaml）的强类型形态。 |
| `RulesJudge` | 确定性规则评审引擎：执行 hidden/judge.rules.yaml 中的全部检查项。 |
| `ScriptJudge` | 脚本评审：把评分逻辑外包给任务自带脚本（python/bash/任意可执行），覆盖规则引擎表达不了的领域判断。 |

### com.agenteval.report

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `BestAttemptSelector` | 最佳 attempt 选择器：按任务声明的策略从全部 attempt 中挑出计入总结的那一次。 |
| `ReportGenerator` | 报告生成器：从 run 目录的既有工件（meta / run_state / judge / trace / inbox） 纯读地重建评估报告，输出机器可读的 report.json 与人类可读的 report.md。 |

### com.agenteval.runner

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `AutoEvalSampler` | auto-eval 后台采样器（Phase 3，设计 §10）：agent attempt 执行期间按 runtime.auto_eval_interval_seconds 间隔快照当前 workspace 并跑一次隐藏评审，产出「进行中的得分轨迹」。 |
| `RunManager` | Runner：一次评估 run 的全生命周期编排。 |
| `SuiteRunner` | 任务集批跑器：用指定的 Agent对任务库中的每个任务执行一次或多次评估，汇总为「套件报告」；并支持多个 Agent 并列跑同一任务集，产出选型对比面板。 |

### com.agenteval.state

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `RunMeta` | run 元数据（meta.json）：把「这次评的是哪个任务、谁在答题、哪个引擎在评」 固化到运行目录，使运行目录自包含——工具网关子进程、离线报告再生、resume 都只凭 run 目录即可还原上下文。 |
| `RunState` | resume 快照（run_state.json）：每个 attempt 结束后原子更新，进程被杀后凭它 + trace 精确恢复进度（轻量 Auto-Resume）。 |
| `RunStateStore` | run_state.json 的读写器。 |
| `RunStatus` | run 的生命周期状态。 |

### com.agenteval.submission

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `SubmissionManager` | 提交管理器：Agent 结构化提交的守门人。 |
| `SubmissionValidationResult` | 提交校验结论。 |

### com.agenteval.task

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `FeedbackLevel` | 回传给 Agent 的反馈粒度。 |
| `InstructionsRenderer` | 渲染 Agent 视角的任务说明 instructions.md。 |
| `JudgeType` | Judge 执行类型。 |
| `SelectionPolicy` | 最佳 attempt 选择策略（借鉴 SForge 的 selection 概念，简化为三种）。 |
| `TaskContext` | 一次 run 的路径上下文：任务目录 + 运行产物目录的全部落点，构造后不可变。 |
| `TaskSpec` | 任务规格（task.yaml 的强类型形态）——一次评估的单一事实来源。 |
| `TaskSpecException` | 任务规格非法异常：聚合全部校验错误一次性抛出（fail-fast，配错的任务根本不允许起跑）。 |
| `TaskSpecLoader` | task.yaml 加载器：解析 + 默认值归一 + 全量校验，任一错误即拒绝起跑（fail-fast）。 |
| `TaskTier` | 任务分层：给任务库中的任务标注「批跑意图」，供 suite --tier 做子集过滤。 |
| `TaskType` | 任务类型：决定提交分型 schema 的默认选择（builtin:<type>），并进入报告用于横向归类。 |

### com.agenteval.tool

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `ToolAccess` | 一次 run 内「工具调用能力」的载体，由 Runner 交给各适配器。 |
| `ToolCallResult` | 一次网关工具调用的结果。 |
| `ToolGateway` | 工具网关：Agent 调用外部能力的唯一合法通道（allowlist + mock/真实后端 + 全量留痕）。 |
| `ToolGatewayClient` | 常驻工具网关的瘦客户端：agent-eval tool call 经此连到框架进程内的 ToolGatewayServer，由服务端代写签名 trace 事件。 |
| `ToolGatewayServer` | 常驻工具网关服务：把「调用工具」从 Agent 自跑的独立进程收敛为框架进程内的单点服务。 |
| `ToolHttpBackend` | 工具真实 HTTP 后端执行器（live 模式专用）：按任务静态声明外呼，并把每次交换 以 mock 应答库同格式存档——「真实调用」与「可复现」在此闭合。 |
| `ToolMode` | 工具网关的运行模式：决定「声明了真实后端的工具」如何被服务。 |

### com.agenteval.trace

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `OtlpTraceExporter` | OTLP/OpenInference 导出适配器：把 run 目录的 trace.jsonl 纯读地转换为 OTLP/JSON（ExportTraceServiceRequest），供 Arize Phoenix / Langfuse / 任意 OTel Collector 直接摄取——自己不建看板，可视化「白嫖」外部生态（调研报告行动 7，PoC D 已验证）。 |
| `TraceEventType` | trace 事件类型全集。 |
| `TraceLogger` | 全过程留痕记录器：append-only JSONL，一行一个事件。 |
| `TraceSecret` | 每 run 的 trace 签名密钥管理：生成、（在 Agent 停止后）落盘、离线加载。 |
| `TraceSigner` | trace 事件的 HMAC-SHA256 签名器：为「事件真伪可核验」提供密码学物证。 |

### com.agenteval.util

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `Dirs` | 目录树复制 / 删除工具。 |
| `Hashes` | SHA-256 指纹工具：为「评分可复现」提供物证。 |
| `Ids` | 运行标识生成器：时间戳前缀 + 短随机尾，兼顾可读性、可排序性与唯一性。 |
| `JsonPaths` | 极简 JSON 路径解析器：支持 $、$.a.b、$.items[0].sku 三种形态。 |
| `Jsons` | JSON / YAML 序列化的单一出入口。 |

### com.agenteval.workspace

| 类 | 职责（Javadoc 首句） |
| --- | --- |
| `WorkspaceManager` | 工作区管理：run 目录初始化、work → workspace 复制、文件级基线指纹。 |

## 测试类索引（src/test/java）

### com.agenteval.agent

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `DockerSandboxTest` | Docker 沙箱参数构造的安全契约回归：挂载矩阵（只挂 Agent 可触碰区、只读位正确）、默认断网、工具网关回连改写、占位符容器路径替换——argv 构造是纯函数，逐项钉死，防止未来改动悄悄把 hidden/任务目录挂进容器或把只读位放开。 |

### com.agenteval.cli

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `EvalsetInitScaffoldTest` | evalset init 脚手架端到端回归：生成的小团队私有测评集骨架必须可读、可解析、不覆盖已有目录，并给后续 task init / suite --agents-file 留出正确接线点。 |
| `HistoryCommandTest` | agent-eval history 聚合回归：跨 run 汇总 report.json，产出 (任务 × Agent) 趋势；红队攻击 run（runs/redteam/ 下）不计入趋势，但其门禁摘要被并入报告。 |
| `SuiteCommandAgentsFileTest` | --agents-file 多 Agent 对比配置的解析回归：合法配置解析出正确规格，非法配置（缺 cmd、类型不合法、标签重复、空列表）给出可读错误而非跑一半才崩。 |
| `ValidateLintTest` | 规则深度 lint 回归：把「run 时才会炸的规则配置错误」（expected_from 断链、schema_file 缺失、要求调用白名单外工具）前移到 validate 静态阶段拦截；内置任务必须全部通过 lint。 |

### com.agenteval.integration

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `AutoEvalSamplingTest` | auto-eval 后台采样端到端回归：用一个「先交草稿、再慢慢完成」的慢 Agent 驱动真实 run，验证设计 §10 的 Phase 3 语义——按间隔快照评审、轨迹进 trace 与 report、绝不回注 Agent、绝不影响正式成绩。 |
| `DockerSandboxRunTest` | Docker 沙箱端到端回归：在真实容器里跑真实任务（无 mock），验证两件事—— |
| `EndToEndScriptedRunTest` | 端到端回归：用脚本回放适配器完整走通「run → 收件 → 判分 → 反馈 → 多轮修正 → 报告」。 |
| `HttpAgentRunTest` | HTTP Agent 适配器端到端回归：起一个真实本地 HTTP 服务扮演服务型 Agent，验证「框架按轮 POST 任务说明 → 服务响应体即提交 → 判分 → 受控反馈 → 修正通过」全链路，以及请求契约（protocol / instructions / feedback / 自定义头）与各失败路径的语义。 |
| `ResumeEndToEndTest` | 轻量 Auto-Resume 端到端测试：进程「猝死」后凭 run_state 续跑，以及 resume 前 hidden 被篡改时的完整性熔断。 |
| `SuiteRunnerTest` | 任务集批跑器的端到端回归：验证 SuiteRunner 能把任务库中每个任务的回放闭环 跑通并正确汇总，同时验证「过滤 / 报告落盘 / 全通过判据」这三条 CI 门禁依赖的能力。 |
| `TaskInitScaffoldTest` | task init 脚手架端到端回归：生成的任务必须开箱即用—— 静态体检（validate + 深度 lint）通过，且 scripted 回放走完「失败 → 受控反馈 → 修正通过」闭环。 |
| `TasksValidationTest` | 任务库门禁测试：5 个示例任务必须始终 ①规格合法 ②规则文件合法 ③pass 样例能过提交校验。 |
| `UsageReportingTest` | Agent 自报 usage（token/成本）链路的端到端回归：信封携带可选 usage → 校验通过 → trace 留痕（usage_recorded，签名） → report.json 聚合出 cost 节点 → report.md 呈现成本段落。 |

### com.agenteval.judge

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `FeedbackPolicyTest` | FeedbackPolicy 裁剪策略测试：各粒度回传内容边界 + private_notes 永不出闸。 |
| `JudgeRunnerTest` | JudgeRunner 聚合计分模型测试：维度归一、一票否决、反馈文案与结果 schema 自检。 |
| `LlmRubricJudgeTest` | llm_rubric 检查回归：起本地 HTTP 服务扮演 OpenAI 兼容判分端点，验证请求契约（temperature 0 / rubric 注入 / 防注入包裹）、部分得分聚合、非确定性如实标注、原始响应存档，以及 fail-closed 语义（未配置 / 持续失败）。 |
| `LlmRubricRedTeamTest` | llm_rubric 框架侧红队回归：非确定性 LLM 判分的滥用面集中在「框架侧」—— 被评内容里夹带越权指令、判分模型返回越界/违约结论、模型持续不可用。 |
| `RulesJudgeHardeningTest` | 三项可信硬化在判分侧的行为测试：trace 签名核验（P0-2）、command nonce 防短路（P0-3）、canary 扩大扫描（P0-1）。 |
| `RulesJudgeTest` | RulesJudge 全部检查类型的行为测试——判分引擎是框架的信任根基，逐类型覆盖。 |
| `RulesJudgeWorldStateTest` | world_state 终态比对检查的行为回归（借 tau-bench「比世界终态而非比嘴」）。 |
| `ScriptJudgeTest` | ScriptJudge 的脚本契约测试：stdout JSON 解析、环境变量注入、故障即抛。 |

### com.agenteval.report

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `BestAttemptSelectorTest` | BestAttemptSelector 三种策略的选择行为测试。 |
| `ReportGeneratorTest` | ReportGenerator 的报告重建行为测试：从 run 工件纯读重建 report.json/report.md，工具统计只计入签名可核验的 tool_call 事件（与判分同口径，防伪造统计混入报告）。 |

### com.agenteval.submission

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `SubmissionManagerTest` | SubmissionManager 四步校验（解析/信封/绑定/分型）的行为测试。 |

### com.agenteval.task

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `TaskSpecLoaderTest` | TaskSpecLoader 的加载、默认值与 fail-fast 校验测试。 |

### com.agenteval.testsupport

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `TestSpecs` | 测试用 TaskSpec 构造器：绕过 YAML 直接构造合法规格，让判分测试聚焦规则本身。 |

### com.agenteval.tool

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `ToolBackendLiveReplayTest` | 真实工具后端（Phase 3）端到端回归：起真实本地 HTTP 服务扮演工具后端，验证「live 真实外呼 → 响应存档 → 存档晋升为应答库 → 默认 replay 确定性回放」的完整闭环，以及白名单/凭证/失败路径的 fail-closed 语义。 |

### com.agenteval.trace

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `OtlpTraceExporterTest` | OTLP/OpenInference 导出适配器的端到端回归：用真实 run 的 trace 驱动，验证 span 结构（AGENT/CHAIN/TOOL 三层）、父子区间合法性、确定性（幂等导出） 与 OTLP/HTTP 推送（本地 HTTP 收集器实收实测）。 |
| `TraceLoggerTest` | TraceLogger 的 JSONL 追加、跨实例续号与事件 schema 自检测试。 |
| `TraceSignerTest` | TraceSigner 与签名版 TraceLogger 的核验测试：这是「trace 不可伪造」（红队 P0-2）修复的信任根基，覆盖正例、篡改、伪造与错误密钥。 |

### com.agenteval.util

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `JsonPathsTest` | JsonPaths 的行为契约测试：支持的三种形态 + 非法语法拒绝。 |

### com.agenteval.workspace

| 测试类 | 覆盖点（Javadoc 首句） |
| --- | --- |
| `WorkspaceManagerTest` | WorkspaceManager 的目录初始化与基线指纹测试（基于真实示例任务）。 |

## judge 规则引擎 check 类型

来源：`judge/RulesFile.SUPPORTED_TYPES`（分派实现在 `judge/RulesJudge`；各类型语义详见 README「写一个新任务」）。

- `json_schema`
- `jsonpath_equals`
- `jsonpath_exists`
- `jsonpath_matches`
- `list_coverage`
- `evidence_sources_valid`
- `workspace_file_exists`
- `workspace_file_contains`
- `changed_files_verified`
- `command`
- `tool_call_required`
- `tool_call_forbidden`
- `no_canary_leak`
- `world_state`
- `llm_rubric`

## trace 事件类型

来源：`trace/TraceEventType`（trace.jsonl 中以小写书写）。

| 事件 | 语义 |
| --- | --- |
| `run_started` | run 生命周期开始（含任务/agent/指纹元数据）。 |
| `agent_started` | 一次 agent attempt 启动。 |
| `agent_finished` | 一次 agent attempt 结束（含退出码与日志引用）。 |
| `tool_call` | 经框架网关的工具调用（含 call_id 与 mock 标记）。 |
| `shell_command` | 框架代跑的 shell 命令（judge command 型 check 等）。 |
| `submission_received` | 收到提交文件。 |
| `submission_invalid` | 提交未通过 schema 校验。 |
| `judge_started` | 评审开始。 |
| `judge_completed` | 评审完成（含分数与指纹）。 |
| `auto_eval_sampled` | auto-eval 后台快照采样结果（kind=auto，不回注 Agent，只供轨迹观测）。 |
| `feedback_delivered` | 受控反馈已写出。 |
| `stop_hook_triggered` | Agent 过早自称完成、被要求继续（轻量 stop hook）。 |
| `resume` | 从 run_state 恢复继续执行。 |
| `error` | 框架内部错误。 |
| `usage_recorded` | token / 成本记录（可选，取决于 agent 能否导出）。 |
| `final_selection` | 最佳 attempt 选择结果。 |
| `run_completed` | run 生命周期结束。 |

## 任务库（tasks/）

| 任务 | 类型 | tier | 名称 |
| --- | --- | --- | --- |
| `api-payload-001` | api_payload | smoke | 按接口文档生成创建订单 payload |
| `code-fix-001` | code_fix | smoke | 修复价格计算器的越界与空指针缺陷 |
| `doc-analysis-001` | document | regression | 从版本发布说明中提取破坏性变更 |
| `prd-review-001` | review | domain | 评审会员积分兑换功能 PRD |
| `tool-call-001` | tool_call | security | 查询用户信用等级并真实开卡 |

---

统计：生产类 74 个 · 测试类 33 个。缺 Javadoc 的类会在上表显式标记（本地图以 Javadoc 首句为数据源，请随手补齐）。
