# MCP 环境适配器设计与规划

> 日期：2026-09-16
> 状态：设计定稿，待实施
> 任务卡：`docs/tasks/TASK-MCP-00…10`，索引见 [docs/11-MCP任务卡.md](11-MCP任务卡.md)
> 前置：[docs/08](08-投入使用路线设计.md)（投入使用路线）、[docs/03](03-AgentEval-Lite-设计方案.md) §9.3 / §10（隔离与适配器）

## 0. 一页结论

**做什么**：新增 `mcp` 适配器——框架把"任务环境"作为 MCP server 暴露给被测 Agent（`get_task` / `workspace_*` / `call_tool` / `submit` / `get_feedback`）。被测 Agent 只要能配置一个 MCP server 就能被评测，不需要 shell、不需要 HTTP 服务、不需要 Docker 镜像。

**为什么值得**：一是覆盖面——Claude Desktop、Cursor、Codex、Claude Code、企业 Agent 平台以及大多数本地 Agent 框架都支持 MCP client，其中相当一部分不支持 cli 接入；二是信任模型——Agent 只能通过 server 触碰 workspace，`hidden/`、`judge/`、`traces/`、run 目录对它结构性不可见，且每一次读写提交都进 HMAC trace。这是不依赖 Docker 的强隔离与全程留痕，正好补上现有 cli 模式"同机同用户只能靠约定"的短板。

**不改什么**：inbox 文件仍是唯一计分通道（`submit` 工具最终写的还是 `inbox/attempt_NNN.json`），SubmissionManager / JudgeRunner / FeedbackPolicy / ReportGenerator / 红队门禁全部不动。MCP 只是第五种 `AgentAdapter`，不是第二套评测内核。

**怎么推进**：先 tracer bullet（`get_task` + `submit` 两个工具、stdio、单任务单会话）打通全链路并用真实 client 握手，再补 workspace / call_tool、trace、红队、文档；Streamable HTTP、suite 会话、操作者工具面放 v2。整体优先于局部：合约先冻结，传输层与工具集按最小集迭代，任何一张卡都不允许为了"更完整"去改评测内核。

## 1. 问题与目标

### 1.1 现状

- 四种适配器：manual / scripted / cli / http。cli 与 http 都要求 Agent"能被框架拉起"或"是个常驻服务"。
- 大量 Agent 产品的对外能力面只有 MCP client：Claude Desktop、ChatGPT 桌面连接器、企业低代码 Agent 平台、以 LangGraph / AutoGen 等框架托管的 Agent。它们接不进来。
- 宿主直跑的 cli 模式下 Agent 与框架同机同用户，`hidden/` 只靠约定与 canary 守（README「安全边界」）；强隔离要走 Docker，多一层运维成本。

### 1.2 目标

| # | 目标 | 可验证判据 |
| --- | --- | --- |
| G1 | 任何 MCP client 都能作为被测 Agent 完成一次 run（多轮反馈修正） | Codex CLI 仅配置评测 MCP server 即跑通 `api-payload-001` fail→pass；官方 Java SDK client 进程内 e2e 全绿 |
| G2 | 被测 Agent 结构性接触不到评审禁区 | 红队 M 系列（路径穿越、绝对路径、符号链接、伪造 attempt_id、越权工具）全部 DEFENDED，基线 0 |
| G3 | Agent 的每次动作可核验 | 每个 MCP 工具调用落一条 HMAC 签名 trace 事件；report 的 tool_usage / 新 `agent_actions` 统计只认签名事件 |
| G4 | 判分内核零改动 | `SubmissionManager` / `JudgeRunner` / `RulesJudge` / `ScriptJudge` 的判分逻辑不改；`FeedbackPolicy` / `InstructionsRenderer` / `ReportGenerator` 只加通道变体或统计段，`SubmissionChannel` 默认 `FILE` 时既有测试（含 `FeedbackPolicyTest` 钉死的 inbox 绝对路径）无一修改仍全绿 |
| G5 | 单 jar、零外部服务的定位不变 | 无新常驻服务；stdio 模式下 client 拉起框架进程即评测 |

### 1.3 非目标（v1 明确不做）

- 不做 Streamable HTTP / SSE 远程传输（v2）。
- 不做多任务会话与 `suite --agent mcp`（v2）。
- 不做"操作者"工具面（让 AI 助手用 MCP 驱动 `run` / `suite` / `report`；v2，且必须与被测者工具面物理分离）。
- 不做 MCP resources / prompts / sampling，只用 tools。
- 不承诺对"同时挂了其他文件系统 / shell 工具的 Agent"有隔离效果（见 §6）。

## 2. 总体架构

### 2.1 控制反转

cli / http 是**框架拉起 Agent**；MCP 是**Agent 连框架**。v1 用 stdio：MCP client 按配置启动 `agent-eval mcp-serve --task <dir>` 子进程，进程生命周期 = 一次 run。

```text
MCP client（被测 Agent 宿主）
   │ spawn（stdio）
   ▼
agent-eval mcp-serve --task tasks/api-payload-001 --runs-root runs --label claude-desktop
   │
   ├─ RunManager.execute(config{adapter = McpAgentAdapter})        ← 与 run 命令同一条内核
   │     ├─ WorkspaceManager.prepare / instructions / trace / ToolGatewayServer  （不变）
   │     └─ runLoop: 每轮 adapter.runAttempt(input) ── 阻塞等待本轮 submit ──┐
   │                                                                          │
   └─ McpEnvironmentServer（stdio JSON-RPC）                                   │
         tools: get_task · workspace_list · workspace_read · workspace_write   │
                · call_tool · submit · get_feedback                            │
         submit 处理器：写 inbox/<attempt>.json → 唤醒 runAttempt ─────────────┘
                        → 等 RunManager 判分写完 feedback → 把反馈作为工具返回值同步给 Agent
```

关键设计：**`runAttempt` 的阻塞语义正好承载控制反转。** 适配器不拉起任何进程，只是等 `submit`。这样 `RunManager` 的多轮循环、超时预算、stop-hook、resume 全部原样成立。

### 2.2 SPI 的最小扩展

为了让 `submit` 的返回值里带上判分反馈（这是 MCP 模式最大的体验优势：Agent 不用再去读反馈文件），并让适配器能用**同一把签名 TraceLogger** 落 `agent_action` 事件，`AgentAdapter` 增加三个 **default 方法**，其余四个适配器零改动：

```java
/** run 环境就绪（trace 已开、工具网关已起）后、首轮 runAttempt 之前调用一次；默认空实现。 */
default void onRunStarted(RunEnvironment env) {}        // env = {TaskContext, TraceLogger, ToolAccess, RunConfig}

/** RunManager 在本轮判分并写出反馈后调用（合法与非法提交两条路径）；默认空实现。 */
default void onAttemptJudged(String attemptId, Path feedbackFile) {}

/** RunManager 在 run 终态确定后、RUN_COMPLETED 事件与 trace.close() 之前调用（无论何种终态）；默认空实现。 */
default void onRunFinished(RunStatus status, String reason) {}
```

调用点（对照 `RunManager` 现状）：`onRunStarted` 在 `runLoop` 入口；`onAttemptJudged` 在非法提交与合法提交两处写完 feedback 之后、`cooldown` 之前（无提交轮不调用——没有挂起的 RPC）；`onRunFinished` 在 `finalize` 开头，这样 resume 指纹失败、judge 故障、正常收尾三条终态路径都覆盖。

**禁止第二把 TraceLogger**：`agent_action` 必须经 `env.trace()` 落盘。再 `open` 一把会打断 seq 连续性，被 `traceIntegrityProblem` 判成 ERROR，且事件可能无 HMAC。

**挂起响应只完成一次**：`onAttemptJudged` 到达时，若反馈里有 `next_attempt_id`（还有下一轮）→ 立即用反馈完成 `submit` 响应；若没有（本轮是终局：通过、用尽、单发）→ 暂存反馈，等 `onRunFinished` 到达后合并成 `{…feedback, run_finished:true, status}` 一次完成。这样 Agent 在末轮既拿到分数也拿到终态。

`get_feedback` 不是兜底而是 v1 必备：client 对长时工具调用的超时不可控（协议本身没有标准 tool timeout），`submit` 超过阈值先返回 `{accepted:true, pending:true}`，Agent 用 `get_feedback` 取结果。

### 2.3 包与依赖方向

```text
cli ─► runner ─► { agent, mcp, judge, submission, tool, trace, report, state, workspace } ─► task ─► util
                    ▲
        mcp ────────┘ （mcp 依赖 agent / task / tool / trace / util；agent 不依赖 mcp）
```

- `com.agenteval.mcp`：`McpEnvironmentServer`（传输 + JSON-RPC 分派）、`EnvironmentTools`（7 个工具的纯函数实现）、`WorkspacePathGuard`（路径安全）、`McpAgentAdapter`（实现 `AgentAdapter`）。
- `cli/McpServeCommand`：`agent-eval mcp-serve`。
- 根 `AGENTS.md` 依赖方向一行同步加 `mcp`。

### 2.4 工具面合约（v1 冻结）

| 工具 | 入参 | 返回 | 留痕 |
| --- | --- | --- | --- |
| `get_task` | — | `{task_id, attempt_id, attempt_number, max_attempts, instructions(markdown), submission_type, allowed_tools[]}` | `agent_action{tool:get_task}` |
| `workspace_list` | `{path?: string}` | `{entries:[{path, type, size}]}`（相对 workspace，深度 1） | `agent_action` |
| `workspace_read` | `{path, offset?, limit?}` | `{path, content, truncated, size}`（UTF-8 文本；二进制返回 base64 并标记） | `agent_action` |
| `workspace_write` | `{path, content, mode?: overwrite\|append}` | `{path, bytes}` | `agent_action`（含 sha256） |
| `call_tool` | `{name, input}` | `{call_id, success, output}` | 复用 `tool_call`（ToolGateway 代写签名事件） |
| `submit` | `{submission: object}` | `{accepted, valid, score?, passed?, feedback, failed_checks[], next_step, next_attempt_id?, run_finished, status?}` | `submission_received` 等既有事件 + `agent_action{tool:submit}` |
| `get_feedback` | `{attempt_id?}` | 反馈 JSON 对外字段（同 feedback 文件） | `agent_action` |

合约约束：

- `submit.submission` 里的 `attempt_id` / `task_id` 若与服务端当前值不一致 → 直接 `valid:false` 并说明，不落 inbox（防伪造轮次）；缺省时服务端**补齐**当前 attempt_id（降低 client 出错率，与文件通道语义等价）。
- 所有路径参数经 `WorkspacePathGuard`：拒绝绝对路径、`..`、以及 realpath 逃出 workspace 的符号链接；单次 `workspace_write` 与 `submit` 有大小上限（默认 2 MiB / 1 MiB，可由 `runtime` 配置覆盖）。
- 工具结果按 MCP `CallToolResult` 包装：`content: [{type:"text", text:<JSON 字符串>}]` +（client 支持时）`structuredContent`；错误用 `isError:true`，`{code, message}` 是 content 载荷而不是 JSON-RPC error——协议级 error 只用于非法帧 / 未知 method，工具级错误必须让模型看得到才能自我修正。
- `initialize` 做协议版本协商（以所选 SDK 支持的最新已发布修订为准）、返回 `serverInfo` 与 `capabilities.tools`，收到 `notifications/initialized` 后才接受 `tools/call`；取消经 `notifications/cancelled`，v1 只做"忽略并记日志"。
- 工具描述（description）就是 Agent 看到的"使用说明"，与 `instructions.md` 一致；`instructions.md` 在 MCP 模式下渲染"提交方式 = 调用 `submit` 工具"，而不是文件路径（见 §3.4）。

### 2.5 传输

- **v1 stdio**：stdout 只走 JSON-RPC，**每帧单行 NDJSON**（禁止 pretty-print）；框架所有日志走 stderr（slf4j-simple 默认即 stderr，但属性必须在任何 `LoggerFactory` 调用之前设定），picocli 的解析与用法输出必须在进入会话之前结束，`mcp-serve` 不向 stdout 打印任何摘要。run 结束后 server 关闭 stdio，client 观察到进程退出。v1 不支持 `--resume`。
- **v2 Streamable HTTP**：在 `ToolGatewayServer` 同款 JDK `HttpServer` 上手写（POST JSON-RPC + 可选 SSE），token 鉴权，一进程多会话（每会话一 run）。官方 SDK 的 HTTP 传输依赖 Servlet / Spring，不引入。

### 2.6 依赖决策（TASK-MCP-00 spike 定案）

两个候选：

| 方案 | 优点 | 代价 |
| --- | --- | --- |
| 官方 `io.modelcontextprotocol.sdk:mcp` | 协议正确性与版本演进跟随官方；client 端可用于测试 | 引入 Reactor；新版本默认 Jackson 3，需用 Jackson 2 兼容模块或对齐版本；fat jar 增大 |
| 手写 stdio JSON-RPC 子集（initialize / tools/list / tools/call / ping） | 零依赖，符合项目"少依赖"取向；代码量小（~400 行） | 协议演进要自己跟；边角（notifications、cancellation）要自己补 |

判据：官方 SDK 能在 Jackson 2.x 下编译、fat jar 增量 < 5 MB、Codex 与 SDK client 握手通过 → 用 SDK；否则手写。**测试侧无论如何用官方 SDK client 做握手回归**（作为 test 依赖不进产物）。

## 3. 关键设计点

### 3.1 attempt 生命周期（适配器内部状态机）

```text
IDLE ──runAttempt(input)──► WAITING_SUBMIT(attempt_id, deadline)
   WAITING_SUBMIT ──submit(ok)──► SUBMITTED(pending response) ──return AttemptOutcome.submitted(file)
   SUBMITTED ──onAttemptJudged(feedback)──► 完成挂起的 submit 响应 ──► IDLE（等下一轮 runAttempt）
   SUBMITTED ──onRunFinished(status)──► 完成挂起的 submit 响应（run_finished:true）──► CLOSED
   WAITING_SUBMIT ──deadline──► return AttemptOutcome(null, false, 0, null, exhausted=false)   （本轮无提交，按现有语义记 invalid）
   任意 ──client 断开 / stdio EOF──► return noMoreInput()（exhausted=true，run 以 agent_exhausted 结束）
```

- `get_task` 在 `WAITING_SUBMIT` 之外调用（如首轮尚未开始、或上一轮已提交等待判分）返回当前快照并带 `state` 字段，不报错。
- 同一轮重复 `submit` → 第二次返回 `isError`：本轮已提交，等待反馈。
- `submit` 响应的挂起上限 = `attempt_timeout` 与 client 超时中较小者减安全余量（阈值由 TASK-MCP-00 实测写回）；超过则先返回 `{accepted:true, pending:true}`，Agent 用 `get_feedback` 取结果。
- `submit` 写 inbox 与 gate 状态切换在 `AttemptGate` 内原子完成：文件已落盘就一定返回 `submitted`，不会出现"文件在、runAttempt 却按超时记无提交"。
- MCP 的 `AttemptOutcome.agentDeclaredDone` 为 **false**（Agent 没有"退出"，只是提交了一轮），避免每个未过线轮次都多打一条 `stop_hook_triggered`；信封里 `needs_human_review` 等语义不变。

### 3.1a 线程模型（必须遵守，否则 stdio 会话卡死）

stdio 是一条共享的双向通道。若 `tools/call` 的处理在读循环线程上阻塞等待判分，`ping`、`notifications/cancelled`、`get_feedback` 都进不来，会话即死锁。

```text
线程 A（main）      ：RunManager.execute → runLoop → adapter.runAttempt 阻塞在 AttemptGate
线程 B（stdio 读）  ：逐行读帧 → 解析 → 交给线程池，立即回到读下一帧；绝不 join 任何工具处理
线程池 C（工具处理）：workspace_* / call_tool 同步完成；submit 只做"写 inbox + 切 gate + 返回未完成 Future"，
                     响应由线程 A 在 onAttemptJudged / onRunFinished 里 complete，再由写线程序列化到 stdout
写线程 D            ：唯一向 stdout 写帧的线程，保证帧不交错
```

### 3.2 路径安全（`WorkspacePathGuard`）

- 输入必须是相对路径；`Path.of(p).isAbsolute()` 拒绝；`normalize()` 后任何 `..` 段拒绝。
- 解析后 `toRealPath()`（存在时）必须以 workspace 的 realpath 为前缀；不存在的写目标要求其父目录 realpath 在 workspace 内。
- 不允许通过工具创建符号链接（没有此工具）；已存在的符号链接按 realpath 判定，指向外部即拒绝读写。
- 拒绝写入 `.ael/` 前缀（框架私有区，虽然不在 workspace 内也一并防御性拒绝）。
- 写入必须不跟随已存在的符号链接：目标若是符号链接一律拒绝（`Files.write` 默认跟随，M3 不能只测读）；创建父目录时同样按 realpath 校验。
- 拒绝路径中的控制字符；比较一律用 realpath，不做字符串前缀比较（大小写不敏感文件系统）。
- **不**与 `RulesJudge.existsInsideWorkspace` 合并：后者只做 `normalize` + 前缀判断、允许跟随符号链接，是判分器对"引用是否指向材料"的宽口径；PathGuard 是对 Agent 写读的严口径。两者语义不同，强行复用会改变判分行为。

### 3.3 留痕与报告

- 新增 trace 事件 `agent_action`：payload `{tool, path?, bytes?, sha256?, success, error_code?}`。`workspace_read` 只记路径与字节数，不记内容（避免 trace 膨胀与二次泄露面）；`workspace_write` 记 sha256。
- schema `trace.event.schema.json` 枚举加 `agent_action`；`TraceLoggerTest` 覆盖；`OtlpTraceExporter` 走 default 分支即可（不新增 span 类型）。
- `ReportGenerator` 增 `agent_actions` 统计段（按工具计数、写入文件数、被拒绝次数），**只统计签名可核验事件**，与 `tool_usage` 同口径。
- `changed_files_verified` 等既有 check 不变；它们依赖 workspace 指纹对比，MCP 写入同样改变文件，天然兼容。

### 3.4 instructions 与反馈文案的通道变体

`InstructionsRenderer` 新增 `SubmissionChannel { FILE, MCP_TOOL }`：MCP 模式下"你的工作区"一节改为"经 `workspace_list/read/write` 访问，路径相对工作区根"、"工具调用方式"一节改为"调用 `call_tool`"（不再出现 `agent-eval tool call`）、"提交方式"一节改为"调用 `submit` 工具，提交信封见工具描述"（不再出现 inbox 绝对路径）、"多轮反馈"一节改为"`submit` 的返回值即反馈，也可 `get_feedback`"。四处都要改，只改提交段不够。`FeedbackPolicy.next_step` 在 MCP 模式下写"调用 `submit` 提交下一轮"。渠道由 `RunConfig` 传入（adapter 决定）。

### 3.5 日志与 stdout 纪律

stdio 传输下任何写到 stdout 的非 JSON-RPC 字节都会毁掉会话。措施：`mcp-serve` 启动即把 SLF4J simple 重定向到 stderr；`RunManager` 现有 `log.info` 全走 SLF4J（已是）；`McpServeCommand` 不用 `System.out`；单测断言 server 进程 stdout 只含合法 JSON-RPC 帧。

### 3.6 与 Docker / cli 的关系

- MCP 模式的隔离前提是"Agent 只有评测 server 这一个工具面"。对不可信 Agent，仍可把 **Agent 宿主**放进容器，MCP server 在宿主侧跑（stdio 需同机；v2 HTTP 可跨机）。
- Codex / Claude Code 这类自带 shell 的 Agent，用 cli 模式评的是"真实工作方式"，用 MCP 模式评的是"只用受控工具面"；报告 `run.adapter` 已能区分，文档要写明两者衡量的能力不同，不要混比。

## 4. 分阶段路线与整体约束

### 4.1 里程碑

| 里程碑 | 内容 | 判据 |
| --- | --- | --- |
| **MM0 决策** | spike：SDK vs 手写；真实 client（Codex）与 SDK client 握手 | `docs/10` §2.6 判据落实，决策写回本文 |
| **MM1 tracer bullet** | `get_task` + `submit` + `get_feedback` 三个工具、stdio、`mcp-serve`、SPI hooks、instructions 变体 | SDK client 进程内 e2e：api-payload-001 fail→pass；Codex 仅配 MCP server 跑通一次 |
| **MM2 完整工具面 + 可信链** | `workspace_*`、`call_tool`、PathGuard、`agent_action` 事件 | tool-call-001 经 MCP 通过（真实工具调用留痕）；`mvn verify` 全绿 |
| **MM3 加固与文档** | 红队 M 系列、report `agent_actions` 统计段、SECURITY / README / docs/06 / 导览、Claude Desktop / Cursor / Codex 配置示例 | `redteam/run_all.sh` 基线仍 0；`bash bin/ci-smoke.sh` 全绿 |
| **MM4（v2）** | Streamable HTTP + token、suite 会话（`next_task`）、操作者工具面 | 另开卡，不阻塞 v1 发布 |

### 4.2 整体优先于局部的硬约束

1. **合约先冻结**：TASK-MCP-01 定下 7 个工具的 schema、错误码与留痕字段后，后续卡片只能实现、不能改；要改回到 01 走变更。
2. **内核不动**：任何卡片若需要改 `SubmissionManager` / `JudgeRunner` / `FeedbackPolicy` / `RulesJudge` 判分逻辑，视为设计错误，回到本文重新评估。允许改的只有 `AgentAdapter`（加 default 方法）、`RunManager`（调两个 hook + 传通道）、`InstructionsRenderer`（通道变体）、`ReportGenerator`（新增统计段）、trace schema（加枚举）。
3. **最小工具集起步**：MM1 只有 `get_task` + `submit` + `get_feedback`（后者是 pending 路径的必需件，不是锦上添花）；不是因为别的工具难，而是先证明控制反转 + 同步反馈这条主链在真实 client 上成立。
4. **传输层最简**：v1 只 stdio。HTTP 的鉴权、会话、并发都是另一类问题，不在 v1 混做。
5. **红队与文档是完成的一部分**：MM3 不做完不发 0.6.0。
6. **不为 MCP 单独造第二套报告**：`agent_action` 进现有 report，不出新文件。

### 4.3 风险与应对

| 风险 | 应对 |
| --- | --- |
| SDK 与 Jackson 2 / 项目风格冲突 | MM0 spike 一天内定案；手写方案是可接受的兜底 |
| client 对长时工具调用超时（`submit` 内含判分） | 判分通常 < 3 s；超阈值走 `pending` + `get_feedback`；spike 实测 Codex / Claude Desktop 默认超时 |
| stdout 污染毁会话 | §3.5 纪律 + 单测断言 |
| Agent 同时挂其他工具面导致隔离失效 | 文档明写前提；不可信 Agent 走"Agent 宿主进容器" |
| 协议演进（2025-11 之后 HTTP/auth 仍在变） | v1 只 stdio，最稳定；锁 SDK 版本；CI 用官方 client 握手回归 |
| 局部完美诱惑（resources、prompts、sampling、进度通知…） | 非目标清单 + 硬约束 3；需要时开 v2 卡 |

## 5. 验证策略

| 层 | 做法 |
| --- | --- |
| 单元 | `WorkspacePathGuardTest`（穿越/绝对/符号链接/大小）、`EnvironmentToolsTest`（7 个工具纯函数：正常、错误码、状态机非法调用） |
| 集成 | `McpEnvironmentRunTest`：官方 SDK client 经 stdio 拉起 `mcp-serve` 子进程，跑 api-payload-001 fail→pass、tool-call-001 真实工具留痕、超时无提交、client 断开 → agent_exhausted |
| 协议 | stdout 帧合法性断言；`tools/list` 输出与合约文档一致（schema 快照测试） |
| 红队 | M1 `../hidden` 读；M2 绝对路径读；M3 workspace 内符号链接指向 hidden；M4 伪造 attempt_id/task_id 提交；M5 越权 `call_tool`（复用 G 断言）；M6 超大写入；M7 `workspace_write inbox/attempt_001.json`（落在 workspace/inbox，不算提交，run 记无提交轮）；M8 写入指向 hidden 的已存在符号链接（应拒写） |
| 真实 client | Codex CLI `mcp_servers` 配置只挂评测 server，`--sandbox read-only` 让它没有写文件系统能力，跑 api-payload-001；记录到 `docs/10` 附录 |
| 全量 | `bash bin/ci-smoke.sh`；`mvn verify` 覆盖率不低于现状 |

## 6. 边界声明（写进 README / SECURITY）

- MCP 模式的结构性隔离只对"被测 Agent 的全部工具面就是评测 server"成立；Agent 若同时有 shell 或文件系统工具，隔离退化到与 cli 模式相同的"约定 + 审计"。
- `submit` 同步返回的反馈与 feedback 文件完全相同，不多给一个字；`private_notes` 永不进入任何工具返回值。
- `workspace_read` 内容不进 trace（只记路径与大小），trace 不成为第二份 workspace 副本。
- stdio 模式下 server 进程与 client 同机；跨机接入等 v2 HTTP。

## 附录 B：设计评审记录

2026-09-16 由独立评审（Grok）对照 `RunManager` / `AgentAdapter` / `TraceLogger` / `ToolGatewayServer` 源码评审初稿，结论"有条件同意"。已按评审吸收的修改：

1. 线程模型显式化（§3.1a）：stdio 读循环不得 join 工具处理；`submit` 返回未完成 Future。
2. 新增 `onRunStarted(RunEnvironment)` 钩子，把同一把签名 `TraceLogger` 交给适配器；禁止第二把 logger（§2.2）。
3. 末轮"双完成"问题：有 `next_attempt_id` 立即完成，否则等 `onRunFinished` 合并一次完成（§2.2）。
4. `agentDeclaredDone=false`，避免每个未过线轮次多打 stop-hook（§3.1）。
5. PathGuard 与 `RulesJudge.existsInsideWorkspace` 语义不同，不合并；写入拒绝跟随符号链接（§3.2，M8）。
6. G4 措辞改为"判分内核零改动"，明确允许改动的非判分组件（§1.2）。
7. `get_feedback` 升入 MM1；report `agent_actions` 统计后置到 MM3。
8. 协议事实修正：`CallToolResult.content[]` 包装、initialize/initialized 握手、无标准 tool timeout、取消经 `notifications/cancelled`（§2.4）。
9. M7 口径以卡片为准（写到 workspace/inbox 不算提交），§5 同步。
10. `mcp-serve` v1 不支持 `--resume`（§2.5）。

