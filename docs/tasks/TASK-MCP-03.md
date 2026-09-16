---
id: TASK-MCP-03
title: stdio 传输 + `agent-eval mcp-serve` 命令 + stdout 纪律（tracer bullet 打通）
module: mcp / cli
dependsOn: [TASK-MCP-02]
risk: high
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='McpStdioProtocolTest,McpEnvironmentRunTest' test
  - bash bin/gen-codemap.sh --check
---

# TASK-MCP-03：stdio server 与 CLI 入口

## 0. Meta
- 语言/框架版本：Java 17；依赖按 TASK-MCP-00 决策
- 影响模块/包前缀：`mcp/McpEnvironmentServer`、`cli/McpServeCommand`、`cli/Main`（注册子命令）
- 最小验收命令：`mvn -q -Dtest=McpEnvironmentRunTest test`

## 1. 背景
- 现状：TASK-MCP-02 之后适配器能在内存里完成一次 run；缺传输层与入口。
- 痛点：stdio 模式下 stdout 只能走 JSON-RPC，框架现有日志 / picocli 输出都可能污染会话。

## 2. 目标（Definition of Done）
- [ ] `McpEnvironmentServer`：stdio JSON-RPC，`initialize` / `tools/list` / `tools/call` / `ping`；`tools/list` 直接来自 `EnvironmentToolSpecs`；本卡接线 `get_task`、`submit`、`get_feedback` 三个工具（tracer bullet；`get_feedback` 是 pending 路径必需件），其余在 TASK-MCP-04；`initialize` 版本协商 + 等待 `notifications/initialized` 后才接受 `tools/call`；`notifications/cancelled` 忽略并记 stderr
- [ ] `agent-eval mcp-serve --task <dir> [--runs-root] [--label] [--model]`（v1 无 `--resume`）：picocli 解析完成后才进入会话；slf4j-simple 属性在任何 `LoggerFactory` 调用前设定为 stderr；`System.setOut` 镜像到 stderr 防第三方库直写；stdout 帧一律单行 NDJSON，由唯一写线程序列化；run 结束后关闭 stdio 并以退出码契约（0/1/2）退出
- [ ] `McpStdioProtocolTest`：拉起 `mcp-serve` 子进程，逐帧读取 stdout，断言每一行都是合法单行 JSON-RPC；`initialize` 响应含 `protocolVersion` / `serverInfo` / `capabilities.tools`；未发 `initialized` 前的 `tools/call` 被拒；读循环在一个 `submit` 挂起期间仍能响应 `ping`（线程模型验证）
- [ ] `McpEnvironmentRunTest`（tracer bullet e2e）：官方 SDK client（test scope）经 stdio 拉起 `mcp-serve`，对 `api-payload-001` 先 `submit` 一份错的提交 → 返回含 `failed_checks` 的反馈与 `next_attempt_id` → 再 `submit` 正确提交 → 返回 `run_finished:true, status:PASSED`；run 目录 `report.json` 的 `run.adapter == "mcp"`、`run.agent == label`
- [ ] `README` "其余命令" 加 `mcp-serve` 一行（详细文档在 TASK-MCP-07）

## 3. 范围
### In-scope
- 传输层、CLI 入口、两个工具接线、两类测试
### Out-of-scope
- workspace_* / call_tool / get_feedback 接线（TASK-MCP-04）
- HTTP 传输（v2）

## 4. 约束与关键决策
- 一个 server 进程 = 一次 run = 一个会话；第二个 client 连接（stdio 不会发生）不考虑。
- `mcp-serve` 内部就是 `RunManager.execute(RunConfig{adapter=McpAgentAdapter, channel=MCP_TOOL})`，不复制 `RunCommand` 的逻辑；共用的适配器构造抽到 `SandboxSupport` 同级的辅助类。

## 5. 合约与错误语义
- 协议错误（非法 JSON、未知 method）→ JSON-RPC error 对象；工具级错误 → `isError:true`。
- 退出码：0 run 完成（无论通过与否）/ 1 参数错误 / 2 框架故障。

## 6. Failure Modes & Safeguards
- stdout 污染 → 测试逐帧校验；`System.setOut` 在 `mcp-serve` 内置为 stderr 镜像以防第三方库直写（记录为防御性措施）
- client 提前退出（stdin EOF）→ `AttemptGate.close()` → run 以 `agent_exhausted` 结束，进程正常退出

## 8. 代码改动点
- 文件清单：见 Meta；`pom.xml`（test scope 加 SDK client；产物依赖按 00 决策）

## 9. 测试策略
- 最小集：两类测试
- 推荐回归：`mvn -q verify`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全绿；手工用 Codex `mcp_servers` 配置连一次能列出 3 个工具（记录到 `docs/10` 附录）

## 11. 风险与回退
- 风险：SDK 与项目日志 / 线程模型不合 → 按 00 决策切手写
- 回退：`Main` 移除子命令注册即可隐藏能力
