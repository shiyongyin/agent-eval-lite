---
id: TASK-MCP-04
title: 完整工具面：workspace_list/read/write、call_tool 实现 + `agent_action` trace 事件（TDD）
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
- 影响模块/包前缀：`mcp/EnvironmentTools.java`（+4 个 handler）、`trace/TraceEventType.java`（+`AGENT_ACTION`）、`src/main/resources/schemas/trace.event.schema.json`（enum +`agent_action`）、`mcp/McpEnvironmentServer.java`（注册新工具）
- 最小验收命令：`mvn -q -Dtest=EnvironmentToolsTest test`

## 1. 背景
- 现状：03 后 Agent 只能拿题与提交，看不了材料、改不了文件、调不了工具。
- 痛点：这些动作在 cli 模式下框架不可见；MCP 模式要把它们变成签名事件，这是 G3 的核心。

## 2. 目标（Definition of Done）
- [ ] DoD-1：`TraceEventType.AGENT_ACTION`（Javadoc："被测 Agent 经 MCP 环境工具执行的一次动作（不含内容，只含路径/字节/摘要/结果）"）；schema `type` enum 加 `agent_action`；`TraceLoggerTest` 增一例：记录 `agent_action` 后整文件通过 schema 校验
- [ ] DoD-2：`EnvironmentTools` 通过 `RunEnvironment.trace()` 记 `agent_action`，payload 固定键：`tool`（string）、`path`（string，可无）、`bytes`（long，可无）、`sha256`（string，仅 write）、`success`（bool）、`error_code`（string，失败时）；`workspace_read` 只记 `path/bytes`，**不记内容**
- [ ] DoD-3：`workspace_list`：经 `PathGuard.resolveForRead`（目录）；深度 1；排除 `.ael`；按名排序；`type` 为 `file|dir`（符号链接按目标类型，外指链接跳过并不报错）
- [ ] DoD-4：`workspace_read`：经 `resolveForRead`；`limit` 缺省 `Limits.READ_DEFAULT_LIMIT_BYTES`；内容按 UTF-8 解码，若含非法序列或 `\u0000` → `encoding:"base64"`；`truncated = offset+返回字节 < size`
- [ ] DoD-5：`workspace_write`：经 `resolveForWrite`；`content.length > WRITE_MAX_BYTES` → `TOO_LARGE`；`overwrite` 用 `Files.write(..., CREATE, TRUNCATE_EXISTING, WRITE)`，`append` 用 `APPEND, CREATE`；目标是符号链接 → `INVALID_PATH`（PathGuard 已拒）；父目录不存在 → `createDirectories`；同一 run 内所有 write 串行（单锁）；返回 `sha256`
- [ ] DoD-6：`call_tool`：`env.toolAccess().gateway().call(name, input)`（进程内，签名 `tool_call` 事件由网关代写）；`name` 不在 `spec.allowedTools()` → 先返回 `TOOL_NOT_ALLOWED`（网关本身也会拒并留 `tool_not_allowed`，两层）；返回 `{call_id, success, output, error}`；本工具**不**额外记 `agent_action`（避免双记）
- [ ] DoD-7：`McpEnvironmentServer` 注册 4 个新工具，`tools/list` 与 01 快照仍一致（快照本来就是 7 个）
- [ ] DoD-8：`McpEnvironmentRunTest` 新增 `tool-call-001` 用例：`workspace_read work 材料` → `call_tool user.lookup` → `call_tool card.create`（正确入参）→ `submit`（含两个 call_id）→ `PASSED`；断言 `report.json.tool_usage.total_calls==2`、`FINAL_WORLD_STATE` 在 `passed_rules`、trace 中 `agent_action` 事件数 ≥ 1 且 seq 连续、`traceIntegrityProblem` 为 null（`report.json.run.status_reason` 不含 `trace_integrity`）
- [ ] DoD-9：红队全量仍 19 项 DEFENDED、基线 0（新事件类型不影响既有用例）

## 3. 范围
### In-scope
- Meta 文件；`EnvironmentToolsTest`；`McpEnvironmentRunTest` 增用例
### Out-of-scope
- 报告 `agent_actions` 统计段（06）；合约变更（01）；`changed_files_verified` 等既有 check

## 4. 约束与关键决策
- 只用 `RunEnvironment.trace()` 那把 logger；类内禁止出现 `TraceLogger.open`（`EnvironmentToolsTest` 用反射/源码 grep 断言也可）。
- `OtlpTraceExporter` 不加分支：`agent_action` 走 default 的通用 event 附着（`OtlpTraceExporterTest` 增断言：导出含该事件、不报错）。
- `workspace_write` 不允许创建符号链接（没有此能力），也不跟随已有链接。

## 5. 合约与错误语义
- 按 TASK-MCP-01 §5.2 / §5.4

## 6. Failure Modes & Safeguards
- 大文件读 → `truncated:true`，Agent 用 offset 续读；`limit` 上限 1 MiB 由 inputSchema 卡住
- 并发 write → 单锁串行；并发 read 不加锁
- `call_tool` live 模式外呼超时 → 由 `ToolHttpBackend` 现有超时处理，`success:false, error`

## 7. 可观测性 & 运维
- 每个 handler 结束在 stderr 记 `tool=… success=… ms=…`

## 8. 代码改动点
- 文件清单：见 Meta；测试 `src/test/java/com/agenteval/mcp/EnvironmentToolsTest.java`
- 配置项：无

## 9. 测试策略
- `EnvironmentToolsTest`（直接构造 `RunEnvironment` 用临时 workspace + 真实 `TraceLogger`，不起 server）：
  1. list 根目录：含 work 文件，不含 `.ael`
  2. read 文本：内容与 offset/limit/truncated 正确
  3. read 二进制（写入 `\u0000`）：`encoding:"base64"`
  4. read 不存在 → NOT_FOUND；`../x` → INVALID_PATH
  5. write overwrite / append：字节数与 sha256 正确；父目录自动创建
  6. write 超限 → TOO_LARGE，文件不落
  7. write 到符号链接 → INVALID_PATH，链接目标未变
  8. call_tool 白名单外 → TOOL_NOT_ALLOWED；白名单内（用 `tasks/tool-call-001` 的 mock 应答库）→ 返回 call_id 且 trace 有签名 `tool_call`
  9. 每次 workspace_* 调用后 trace 末条为 `agent_action`，payload 无 `content` 键
  10. inputSchema 校验对 7 个工具逐个生效：多余字段（`additionalProperties`）、缺必填、类型错 → `INVALID_ARGUMENT`，且**不**产生 `agent_action`（校验失败发生在动作之前）
  11. list 传文件路径 → `INVALID_ARGUMENT`；list 不存在目录 → `NOT_FOUND`；list 含外指符号链接的目录 → 该项被跳过、其余正常
  12. read `offset >= size` → `content==""`、`truncated:false`；`offset+limit` 恰好等于 size → `truncated:false`
  13. write `append` 到不存在文件 → 创建；连续两次 append 内容拼接、sha256 为最终内容
  14. 并发：8 线程各写不同文件 + 8 线程读同一文件，无异常、写结果完整、trace seq 连续
  15. Unicode 文件名（`docs/说明.md`）读写正常
  16. `call_tool` 的 `input` 不是 object → `INVALID_ARGUMENT`；网关返回 `success:false`（mock 库无匹配）时 payload `success:false, error` 非空，`agent_action` 不记（由 `tool_call` 事件承载）
- `TraceLoggerTest` / `OtlpTraceExporterTest`：新事件 schema 与导出
- `McpEnvironmentRunTest`：DoD-8

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全绿；红队基线不变

## 11. 风险与回退
- 风险：Agent 高频读写导致 trace 膨胀 → 单条 `agent_action` < 300 B；v2 再考虑采样
- 回退：从 `tools/list` 摘除 4 个工具（快照同步）
