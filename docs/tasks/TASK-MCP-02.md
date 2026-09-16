---
id: TASK-MCP-02
title: McpAgentAdapter + AttemptGate + AgentAdapter 三个生命周期钩子 + SubmissionChannel 通道变体（TDD）
module: agent / runner / task / judge / mcp
dependsOn: [TASK-MCP-01]
risk: high
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='AttemptGateTest,McpAgentAdapterTest,InstructionsRendererChannelTest,FeedbackPolicyTest' test
  - mvn -q -Dtest='EndToEndScriptedRunTest,ResumeEndToEndTest,HttpAgentRunTest,SuiteRunnerTest,MisplacedSubmissionFeedbackTest,CliAgentPathShimTest' test
  - bash bin/gen-codemap.sh --check
---

# TASK-MCP-02：适配器与内核接线（不含传输层）

## 0. Meta
- 语言/框架版本：Java 17；并发只用 `java.util.concurrent`（`CompletableFuture`、`ReentrantLock`/`Condition` 或 `SynchronousQueue`）
- 影响模块/包前缀：
  - `agent/AgentAdapter.java`（+3 default 方法）、新 `agent/RunEnvironment.java`
  - `runner/RunManager.java`（`RunConfig` +1 字段；4 处钩子调用）
  - `task/InstructionsRenderer.java`（+`SubmissionChannel` 参数）、新 `task/SubmissionChannel.java`
  - `judge/FeedbackPolicy.java`（`next_step` 通道变体）
  - 新 `mcp/AttemptGate.java`、`mcp/McpAgentAdapter.java`、`mcp/SubmitResponses.java`
- 最小验收命令：`mvn -q -Dtest='AttemptGateTest,McpAgentAdapterTest' test`

## 1. 背景
- 现状（`RunManager.java`，行号以 `d9653a0` 为准）：`runLoop` 在 199 行；每轮 237 行 `adapter.runAttempt(input)` 阻塞；无提交 → 253 行 `writeInvalid`；提交非法 → 271 行 `writeInvalid` 后 279 行 `cooldown`；判分 → 317 行 `writeJudged` 后 351 行 `cooldown`；judge 异常 301–308 行直接 `return finalize(...)`；`finalize` 在 393 行，401 行 `trace.close()`。
- 痛点：适配器拿不到判分结果，也拿不到那把签名 `TraceLogger`（171 行 `open`，之后只在 runner 内传递）。

## 2. 目标（Definition of Done）
- [ ] DoD-1：`agent/RunEnvironment.java`：`public record RunEnvironment(TaskContext context, TraceLogger trace, ToolAccess toolAccess, String label, SubmissionChannel channel)`
- [ ] DoD-2：`AgentAdapter` 新增（全部 `default`，空实现）：`void onRunStarted(RunEnvironment env)`、`void onAttemptJudged(String attemptId, Path feedbackFile)`、`void onRunFinished(RunStatus status, String reason)`；manual/scripted/cli/http/docker 五个实现类**零改动**
- [ ] DoD-3：`RunManager.RunConfig` 新增字段 `SubmissionChannel channel`（放在 `adapter` 之前），既有构造调用全部传 `SubmissionChannel.FILE`；`run(...)` 静态便捷入口不变
- [ ] DoD-4：`RunManager` 钩子调用点（按现行号）：
  - `onRunStarted(new RunEnvironment(ctx, trace, toolAccess, config.agentName(), config.channel()))`：`runLoop` 开头、第一次构造 `AttemptInput` 之前
  - `onAttemptJudged(attemptId, feedbackFile)`：271 行 `writeInvalid` 之后、279 行 `cooldown` 之前；317 行 `writeJudged` 之后、351 行 `cooldown` 之前（**不**在 253 行无提交分支调用）
  - `onRunFinished(state.status(), state.statusReason())`：`finalize` 方法体第一行（这样 143 行 resume 指纹失败、306 行 judge 故障、388 行正常收尾三条路径都覆盖）
  - `writeInstructions(ctx)` 改为传 `config.channel()`
- [ ] DoD-5：`task/SubmissionChannel.java`：`enum { FILE, MCP_TOOL }`；`InstructionsRenderer.render(TaskContext ctx)` 保留并委托 `render(ctx, SubmissionChannel.FILE)`；`MCP_TOOL` 变体改四节：「你的工作区」（改为"用 `workspace_list` / `workspace_read` / `workspace_write` 访问，路径相对工作区根"）、「可用工具」（改为"调用 `call_tool`，`name` 取下表工具名"，不出现 `agent-eval tool call`）、「提交方式」（改为"调用 `submit` 工具，`submission` 即下述信封；服务端自动补齐 `attempt_id`"，不出现 inbox 路径）、「多轮反馈」（改为"`submit` 返回值即反馈；也可 `get_feedback`"）。信封字段表与评分维度两节不变
- [ ] DoD-6：`FeedbackPolicy.writeJudged / writeInvalid` 增加重载带 `SubmissionChannel`；`MCP_TOOL` 时 `next_step` = `"请调用 submit 工具提交下一轮（attempt_id 由服务端补齐）"`；`FILE` 时文案与现状逐字相同（`FeedbackPolicyTest` 既有断言不改）
- [ ] DoD-7：`mcp/AttemptGate.java`：线程安全状态机 `IDLE → WAITING_SUBMIT(attemptId, deadline) → SUBMITTED → IDLE | CLOSED`；API：`Awaited await(String attemptId, Duration timeout)`（阻塞，返回 `SUBMITTED(file)` / `TIMEOUT` / `CLOSED`）、`SubmitOutcome offer(String submissionJson)`（返回 `ACCEPTED(file, CompletableFuture<JsonNode> response)` / `WRONG_STATE` / `ALREADY_SUBMITTED` / `TOO_LARGE`）、`void close()`（client 断开）、`State state()`；`offer` 内部**原子地**写 `inbox/<attemptId>.json` 并切换状态
- [ ] DoD-8：`mcp/McpAgentAdapter.java` 实现 `AgentAdapter`：`name()="mcp"`；`onRunStarted` 保存 env；`runAttempt` 调 `gate.await(attemptId, input.timeout())`，`SUBMITTED` → `new AttemptOutcome(file, false, 0, null, false)`，`TIMEOUT` → `new AttemptOutcome(null, false, 0, null, false)`，`CLOSED` → `AttemptOutcome.noMoreInput()`；`onAttemptJudged`：读反馈文件对外字段，若含 `next_attempt_id` → 立即 complete 挂起响应（`SubmitResponses.fromFeedback`），否则暂存；`onRunFinished`：若有暂存或未完成响应 → 合并 `run_finished:true, status` 后 complete，再 `gate.close()`
- [ ] DoD-9：`submit` 的 `submission` 若缺 `attempt_id` 由适配器补当前值；若含且不等于当前值 → 不落 inbox，直接返回 `valid:false, schema_errors:["attempt_id 与当前轮次不一致"]`（这是 M4 的防线）；`task_id` 同理
- [ ] DoD-10：既有 e2e 全绿（第二条 `dodCommands`），证明钩子空实现 + `FILE` 默认时内核行为不变

## 3. 范围
### In-scope
- Meta 列出的文件；新测试 `AttemptGateTest`、`McpAgentAdapterTest`、`InstructionsRendererChannelTest`
### Out-of-scope
- stdio / JSON-RPC（03）；`workspace_*` / `call_tool` / `get_feedback` 实现（03/04）
- `SubmissionManager` / `JudgeRunner` / `RulesJudge` / `ScriptJudge` 任何改动

## 4. 约束与关键决策
- **线程模型**（`docs/10` §3.1a）：`offer()` 绝不阻塞等待判分，只写文件、切状态、返回未完成 Future；complete 由 runner 线程在钩子里做。
- **只完成一次**：`SubmitResponses` 内部用 `AtomicBoolean completed`；第二次 complete 静默忽略并 `log.warn`。
- `agentDeclaredDone=false`：避免 345 行 stop-hook 事件每轮触发。
- 适配器只经 `env.trace()` 记事件（本卡尚不产生 `agent_action`，留给 04），禁止 `TraceLogger.open`。
- `RunConfig` 字段新增位置固定在 `adapter` 之前，避免与 `label` 混淆。

## 5. 合约与错误语义
- `offer` 的 `TOO_LARGE` 阈值 = `Limits.SUBMIT_MAX_BYTES`
- `submit` 返回 payload 按 TASK-MCP-01 §5.3

## 6. Failure Modes & Safeguards
- judge 抛异常 → runner 走 306 行 `finalize` → `onRunFinished(ERROR)` → 挂起响应以 `run_finished:true,status:ERROR` 收尾，client 不悬挂
- `await` 超时与 `offer` 同时发生 → `AttemptGate` 用单锁保证：文件已落盘则 `await` 必返 `SUBMITTED`
- `onRunFinished` 在没有挂起响应时到达（如超时轮结束）→ 只 `close()`
- resume 模式（`RunConfig.resumeRunDir != null`）+ mcp adapter → `McpAgentAdapter` 构造时不拒绝，但 v1 `mcp-serve` 不暴露 `--resume`

## 7. 可观测性 & 运维
- `AttemptGate` 状态变化 `log.debug`；complete 两次 `log.warn`

## 8. 代码改动点
- 文件清单：见 Meta
- 配置项：无

## 9. 测试策略
- `AttemptGateTest`（纯状态机，不需 RunManager）：
  1. `await` 前 `offer` → WRONG_STATE
  2. `await` 后 `offer` 合法 → 文件存在于 inbox 且 `await` 返回 SUBMITTED(file)
  3. 同轮第二次 `offer` → ALREADY_SUBMITTED
  4. 超大 submission → TOO_LARGE，文件不落
  5. `await` 超时 → TIMEOUT，状态回 IDLE
  6. `close()` 后 `await` → CLOSED；`offer` → WRONG_STATE
  7. 多线程：10 个线程并发 `offer`，恰一个 ACCEPTED
- `McpAgentAdapterTest`（用真实 `RunManager.run(tasks/api-payload-001, ..., adapter)` 在另一线程驱动，测试线程扮演 client 调 `adapter.submit(json)`）：
  1. 两轮闭环：先交 `samples/attempt-fail.json` → 响应 `valid:true, passed:false, next_attempt_id=attempt_002`；再交 `attempt-pass.json` → 响应 `run_finished:true, status:PASSED, score:100`；run 目录 `report.json` 的 `run.adapter=="mcp"`
  2. `attempt_id` 不一致 → `valid:false`，inbox 无文件，run 该轮记 invalid
  3. 不提交直到超时（用 `TestSpecs` 造 `attempt_timeout` 极小的任务）→ 该轮 `valid:false`，run 继续下一轮
  4. `close()` → run 以 `agent_exhausted` 结束，`onRunFinished` 被调用
  5. 响应只完成一次：末轮 `onAttemptJudged` 与 `onRunFinished` 都到达，client 只收到一个含 `run_finished:true` 且含分数的响应
  6. trace 完整性：`report.json` 无 `trace_integrity_broken`，`traces/trace.jsonl` seq 连续
- `InstructionsRendererChannelTest`：`MCP_TOOL` 渲染结果不含 `inbox/`、不含 `agent-eval tool call`、含 `submit`、含 `workspace_read`；`FILE` 渲染与改动前逐字相同（先在改动前抓一份快照到 `src/test/resources/instructions/api-payload-001.file.md`）
- `FeedbackPolicyTest`：新增 `MCP_TOOL` 通道 `next_step` 断言；既有用例不改

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全绿；`git diff --stat` 里 `SubmissionManager*`、`JudgeRunner*`、`RulesJudge*` 为零改动

## 11. 风险与回退
- 风险：钩子调用点漏某条终态路径 → 测试 4/5 + 对照 §1 行号逐条核对
- 回退：三个 default 方法与 `channel=FILE` 默认值保证既有五个适配器不受影响；删 `mcp/` 即可
