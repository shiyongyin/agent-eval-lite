---
id: TASK-MCP-03
title: stdio JSON-RPC 传输 + `agent-eval mcp-serve` + get_task/submit/get_feedback 接线 + stdout 纪律（tracer bullet）
module: mcp / cli
dependsOn: [TASK-MCP-02]
risk: high
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='McpStdioProtocolTest,McpEnvironmentRunTest' test
  - bash bin/gen-codemap.sh --check
  - "bin/agent-eval mcp-serve --help | rg -q -- '--task'"
---

# TASK-MCP-03：stdio server 与 CLI 入口（打通真实 client）

## 0. Meta
- 语言/框架版本：Java 17；传输实现按 TASK-MCP-00 `### 决策`（SDK 或手写）；测试 client 用官方 SDK（test scope）
- 影响模块/包前缀：新 `mcp/McpEnvironmentServer.java`（分派 + 会话状态）、新 `mcp/StdioTransport.java`（手写方案时）、新 `mcp/EnvironmentTools.java`（本卡只实现 `get_task` / `submit` / `get_feedback` 三个 handler，其余 04 补）、新 `cli/McpServeCommand.java`、`cli/Main.java`（注册子命令）、`README.md`「其余命令」一行
- 最小验收命令：`mvn -q -Dtest=McpEnvironmentRunTest test`

## 1. 背景
- 现状：02 之后适配器能在内存里闭环；缺传输、缺入口。
- 痛点：stdio 下 stdout 只能走协议帧；`RunCommand` 现有 `System.out.println` 摘要、picocli 用法输出、slf4j 都可能污染会话。

## 2. 目标（Definition of Done）
- [ ] DoD-1：`McpEnvironmentServer`：处理 `initialize`（返回 `protocolVersion`=协商结果、`serverInfo{name:"agent-eval-lite",version:Version.ENGINE}`、`capabilities:{tools:{}}`）、`notifications/initialized`（置 ready）、`ping`（返回 `{}`）、`tools/list`（`EnvironmentToolSpecs.toolsListJson()`）、`tools/call`（分派到 `EnvironmentTools`）、`notifications/cancelled`（记 stderr 忽略）；其余 method → JSON-RPC error `-32601`；ready 之前的 `tools/call` → `-32600` "not initialized"
- [ ] DoD-2：`tools/call` 处理在独立线程池执行，读循环线程只解析帧与入队；`submit` handler 拿到 `AttemptGate.offer` 的未完成 Future 后立即返回（响应由 Future 完成时经写线程发出）；只有一个写线程向 stdout 写帧
- [ ] DoD-3：`EnvironmentTools` 本卡三个 handler：`get_task`（从 `RunEnvironment` + `AttemptGate.state()` 组装 §5.2 payload；instructions 用 `InstructionsRenderer.render(ctx, MCP_TOOL)` 的文本）、`submit`（校验 inputSchema → 交 `McpAgentAdapter.submit`）、`get_feedback`（读 `feedback/<attempt>.feedback.json`，缺省最近一轮；不存在 → `NOT_FOUND`）
- [ ] DoD-4：`cli/McpServeCommand`：选项 `--task <dir>`（必填）、`--runs-root`（默认 `runs`）、`--label`（默认 `mcp`）、`--model`；**无** `--resume`；执行顺序：picocli 解析完成 → 设置 `System.setProperty("org.slf4j.simpleLogger.logFile","System.err")`（在任何 `LoggerFactory` 之前，因此放在 `Main` 检测到 `mcp-serve` 子命令时的最早时机，或用静态块）→ `System.setOut(new PrintStream(System.err))` 保存原 stdout 给传输层 → 起 server → `RunManager.execute(new RunConfig(task, runsRoot, model, label, SubmissionChannel.MCP_TOOL, adapter, null))` → run 结束关闭传输 → 退出码 0（完成）/ 1（参数）/ 2（框架故障或 INTEGRITY_BROKEN）
- [ ] DoD-5：所有帧单行 NDJSON（`Jsons.json().writeValueAsString`，禁 pretty），`\n` 结尾，UTF-8
- [ ] DoD-6：`McpStdioProtocolTest`：`ProcessBuilder` 拉起 `java -cp <java.class.path> com.agenteval.cli.Main mcp-serve --task tasks/api-payload-001 --runs-root <tmp>`；用原始帧驱动，断言：(a) stdout 每一行 `Jsons.json().readTree` 成功且含 `jsonrpc:"2.0"`；(b) `initialize` 响应含 `protocolVersion/serverInfo/capabilities.tools`；(c) `initialized` 之前 `tools/call get_task` 返回 error `-32600`；(d) `tools/list` 与快照一致；(e) 发一个 `submit` 后立刻发 `ping`，`ping` 响应在 `submit` 响应之前到达（线程模型验证）；(f) 关闭 stdin 后进程在 10 s 内退出且退出码 0，run 目录 `report.json` 状态 `FAILED/agent_exhausted`
- [ ] DoD-7：`McpEnvironmentRunTest`（tracer bullet e2e，官方 SDK client）：拉起 `mcp-serve`，`initialize` → `tools/list` 有 3 个工具 → `get_task` 返回 `attempt_id=attempt_001, state=WAITING_SUBMIT` → `submit(samples/attempt-fail.json 内容)` → 响应 `valid:true, passed:false, next_attempt_id=attempt_002` → `submit(attempt-pass.json)` → 响应 `run_finished:true, status:PASSED`；断言 run 目录 `report.json`：`run.adapter=="mcp"`、`run.agent==label`、`attempts` 两条、`best_attempt.score==100`
- [ ] DoD-8：`README.md`「其余命令」表加一行 `mcp-serve`（详细文档在 07）
- [ ] DoD-9：手工：Codex `mcp_servers.ael` 配置只挂本 server，`codex exec '列出可用工具'` 输出含 `get_task submit get_feedback`；结果写 `docs/10` 附录 A（先占位一行）

## 3. 范围
### In-scope
- Meta 文件；三个工具 handler；两类测试；README 一行
### Out-of-scope
- `workspace_*` / `call_tool`（04）；HTTP（08）；`--resume`

## 4. 约束与关键决策
- 一进程 = 一 run = 一会话；stdin EOF → `adapter.gate.close()`。
- `mcp-serve` 不复制 `RunCommand` 逻辑，只构造 `RunConfig` 调 `RunManager.execute`。
- 协商版本：server 支持列表按 00 决策；client 请求的版本不在列表时回最新支持版本（协议规定）。
- `get_task.instructions` 与写到 run 目录的 `instructions.md`（MCP_TOOL 变体）内容相同。

## 5. 合约与错误语义
- JSON-RPC error 只用于协议级；工具级错误走 `isError`（TASK-MCP-01 §5.1）
- 退出码沿用框架契约 0/1/2

## 6. Failure Modes & Safeguards
- stdout 被第三方库写入 → `System.setOut` 镜像到 stderr；传输层持有原始 `FileDescriptor.out` 流
- 非法 JSON 帧 → `-32700`，会话继续
- `tools/call` handler 抛异常 → `INTERNAL`，不泄露堆栈
- run 结束但 client 仍发请求 → 返回 `WRONG_STATE`（工具级）；进程在写完最后一帧后退出

## 7. 可观测性 & 运维
- stderr 记：会话开始 / 协商版本 / 每个 tools/call 的 name 与耗时 / 结束原因

## 8. 代码改动点
- 文件清单：见 Meta；`pom.xml`（test scope SDK client；产物依赖按 00）
- 配置项：无新增；Codex 侧 `[mcp_servers.ael] command="…/bin/agent-eval" args=["mcp-serve","--task","…"]`

## 9. 测试策略
- 最小集：`McpStdioProtocolTest`（6 条断言）、`McpEnvironmentRunTest`（1 条主链）
- 推荐回归：`mvn -q verify`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全绿；Codex 能列出 3 个工具（附录 A 有记录）

## 11. 风险与回退
- 风险：SDK 线程模型与 `AttemptGate` 冲突 → 按 00 决策切手写
- 回退：`Main` 移除子命令注册即隐藏能力，其余代码无副作用
