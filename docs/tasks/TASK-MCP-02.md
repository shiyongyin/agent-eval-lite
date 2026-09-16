---
id: TASK-MCP-02
title: McpAgentAdapter + SPI 生命周期钩子 + instructions/next_step 通道变体（TDD）
module: agent / runner / task / judge
dependsOn: [TASK-MCP-01]
risk: high
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='McpAgentAdapterTest,EndToEndScriptedRunTest,ResumeEndToEndTest,HttpAgentRunTest,SuiteRunnerTest' test
  - bash bin/gen-codemap.sh --check
---

# TASK-MCP-02：适配器与内核接线（tracer bullet 的骨架）

## 0. Meta
- 语言/框架版本：Java 17
- 影响模块/包前缀：`agent/AgentAdapter`（+2 default 方法）、`runner/RunManager`（两处 hook 调用 + 通道传递）、`task/InstructionsRenderer`（`SubmissionChannel`）、`judge/FeedbackPolicy`（`next_step` 通道变体）、`mcp/McpAgentAdapter`、`mcp/AttemptGate`（状态机）
- 最小验收命令：`mvn -q -Dtest=McpAgentAdapterTest test`

## 1. 背景
- 现状：`runLoop` 每轮 `adapter.runAttempt(input)` 阻塞后校验 inbox → 判分 → 写 feedback；适配器拿不到判分结果。
- 痛点：MCP 的 `submit` 要同步返回反馈，需要内核在"反馈已写"与"run 已结束"两点回调适配器。

## 2. 目标（Definition of Done）
- [ ] `AgentAdapter` 新增三个 default 方法：`onRunStarted(RunEnvironment env)`（env 含 TaskContext / 同一把签名 TraceLogger / ToolAccess / RunConfig）、`onAttemptJudged(String attemptId, Path feedbackFile)`、`onRunFinished(RunStatus status, String reason)`；四个既有适配器不改
- [ ] `RunManager`：`runLoop` 入口调 `onRunStarted`；非法提交与合法提交两处写完 feedback 之后、`cooldown` 之前调 `onAttemptJudged`（无提交轮不调）；`finalize` 开头、`RUN_COMPLETED` 与 `trace.close()` 之前调 `onRunFinished`（覆盖 resume 指纹失败 / judge 故障 / 正常收尾三条终态路径）；`RunConfig` 增 `SubmissionChannel`（默认 FILE）
- [ ] `McpAgentAdapter`：`runAttempt` 阻塞等待本轮 `submit` 或超时；`AttemptGate` 实现 `docs/10` §3.1 状态机（IDLE / WAITING_SUBMIT / SUBMITTED / CLOSED），线程安全，支持 client 断开 → `noMoreInput()`
- [ ] `submit` 挂起响应只完成一次：`onAttemptJudged` 到达且反馈含 `next_attempt_id` → 立即完成；不含（终局轮）→ 暂存，等 `onRunFinished` 合并 `run_finished:true, status` 一次完成；超过 `pending` 阈值先返回 `{accepted:true, pending:true}`
- [ ] `submit` 写 inbox 与 gate 切换在 `AttemptGate` 内原子；MCP 的 `AttemptOutcome.agentDeclaredDone=false`（不触发 stop-hook 事件）
- [ ] 适配器只经 `env.trace()` 落事件，绝不自行 `TraceLogger.open`（测试断言 run 的 trace seq 连续、`traceIntegrityProblem` 为 null）
- [ ] `InstructionsRenderer` 按 `SubmissionChannel.MCP_TOOL` 渲染四处变体：工作区一节（`workspace_*` 工具）、工具调用一节（`call_tool`，不出现 `agent-eval tool call`）、提交方式（`submit` 工具，不出现 inbox 路径）、多轮反馈（返回值 / `get_feedback`）
- [ ] `FeedbackPolicy.next_step` 在 MCP_TOOL 通道下写"调用 `submit` 提交下一轮"
- [ ] 既有 e2e（scripted / resume / http / suite）全绿，证明 hook 为 no-op 时内核行为不变

## 3. 范围
### In-scope
- 上述文件；`McpAgentAdapterTest`（不依赖传输层：直接以内存方式调用适配器的 `submit(...)` 入口）
### Out-of-scope
- stdio 传输、`mcp-serve` 命令（TASK-MCP-03）
- `SubmissionManager` / `JudgeRunner` / `RulesJudge` 判分逻辑

## 4. 约束与关键决策
- 线程模型按 `docs/10` §3.1a：`submit` 处理器返回未完成 `CompletableFuture`，由 `runAttempt` 所在线程在钩子里 complete；本卡虽无传输层，也要按"处理器不阻塞"写。
- `agentDeclaredDone=false`：MCP Agent 提交后并未退出，与 cli 语义不同，避免每个未过线轮次都多一条 `stop_hook_triggered`。
- 超时无提交：返回 `new AttemptOutcome(null, false, 0, null, false)`，让现有"无提交按 invalid 轮记录"逻辑处理。

## 5. 合约与错误语义
- `submit` 重复调用 → `ALREADY_SUBMITTED`；在非 WAITING_SUBMIT 状态调用 → `WRONG_STATE`（带当前 state）。

## 6. Failure Modes & Safeguards
- 判分抛异常（框架故障）→ `onRunFinished(ERROR)` 仍会被调用，挂起响应以 `run_finished:true,status:ERROR` 收尾，不让 client 悬挂
- client 断开时 `runAttempt` 立即返回 `noMoreInput()`，run 记 `agent_exhausted`

## 8. 代码改动点
- 文件清单：见 Meta

## 9. 测试策略
- 最小集：`McpAgentAdapterTest`：正常两轮（首轮 fail 反馈同步返回、次轮 pass 返回 run_finished）、超时无提交、重复 submit、断开 → exhausted、hook 顺序
- 推荐回归：全部 integration 测试

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全绿

## 11. 风险与回退
- 风险：hook 调用点漏掉某条终态路径（timeout / INTEGRITY_BROKEN / ERROR）→ 用 `RunManager` 现有终态枚举逐条写测试
- 回退：default 方法 + RunConfig 默认值保证回退时四个既有适配器不受影响
