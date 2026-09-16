---
id: TASK-MCP-04
title: 完整工具面：workspace_list/read/write、call_tool + agent_action 留痕（TDD）
module: mcp / trace
dependsOn: [TASK-MCP-03]
risk: medium
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='EnvironmentToolsTest,TraceLoggerTest,OtlpTraceExporterTest,McpEnvironmentRunTest' test
  - bash bin/gen-codemap.sh --check
  - bash redteam/test_gate.sh && bash redteam/run_all.sh
---

# TASK-MCP-04：完整工具面 + 可信链

## 0. Meta
- 语言/框架版本：Java 17
- 影响模块/包前缀：`mcp/EnvironmentTools`（工具实现）、`trace/TraceEventType`（+`AGENT_ACTION`）、`schemas/trace.event.schema.json`
- 最小验收命令：`mvn -q -Dtest=EnvironmentToolsTest test`

## 1. 背景
- 现状：tracer bullet 只有 get_task + submit；Agent 还不能看材料、改文件、调工具。
- 痛点：这些动作在 cli 模式下框架看不见；MCP 模式要把它们变成签名 trace 事件。

## 2. 目标（Definition of Done）
- [ ] `workspace_list / read / write` 经 `WorkspacePathGuard`；read 支持 offset/limit 与二进制 base64 标记；write 支持 overwrite/append 与大小上限
- [ ] `call_tool` 直接调用 run 内 `ToolAccess.gateway().call(name, input)`（进程内，签名 trace 由网关代写），返回 `call_id`；越权工具返回 `TOOL_NOT_ALLOWED`
- [ ] 新 trace 事件 `agent_action`（payload：tool、path?、bytes?、sha256?、success、error_code?），**经 `onRunStarted` 拿到的同一把 TraceLogger 落盘**；schema 枚举、`TraceLoggerTest`、`OtlpTraceExporterTest`（default 分支）同步；e2e 断言 trace seq 连续无 integrity 问题
- [ ] `workspace_write` 目标为符号链接时拒绝（PathGuard 写口径），不跟随
- [ ] `McpEnvironmentRunTest` 增 `tool-call-001` 用例：经 `call_tool` 真实调用 `user.lookup` + `card.create`，`FINAL_WORLD_STATE` 通过，`tool_usage.total_calls == 2`
- [ ] `workspace_read` 内容不进 trace（只记 path / bytes）

## 3. 范围
### In-scope
- 上述实现与测试
### Out-of-scope
- 工具合约变更（回 TASK-MCP-01）
- `changed_files_verified` 等既有 check 的语义

## 4. 约束与关键决策
- `call_tool` 走进程内网关而不是 HTTP 端点：同进程、无需 token，签名由网关代写，与 cli 的 `agent-eval tool call` 留痕同格式。
- `agent_action` 与 `tool_call` 分开：前者是环境操作，后者是任务工具；报告分两段统计。

## 5. 合约与错误语义
- 见 TASK-MCP-01 错误码枚举

## 6. Failure Modes & Safeguards
- 大文件读取 → `truncated:true` + `size`，Agent 用 offset 续读
- 并发写同一文件（client 并行调用）→ 服务端对 workspace 写加单锁，顺序执行

## 8. 代码改动点
- 文件清单：见 Meta

## 9. 测试策略
- 最小集：`EnvironmentToolsTest`（工具正常 / 错误码 / 写到符号链接被拒）、trace 测试
- 推荐回归：红队全量（工具网关与 trace 是安全敏感面）

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全绿；红队 19 项 DEFENDED 基线 0 不变

## 11. 风险与回退
- 风险：trace 事件数量随 Agent 读写激增 → `agent_action` 不含内容，单条 < 300 B；大量读写的任务可在 v2 加采样
- 回退：工具从 `tools/list` 摘除即可
