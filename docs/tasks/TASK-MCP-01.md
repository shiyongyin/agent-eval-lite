---
id: TASK-MCP-01
title: 合约冻结：7 个工具 schema / 错误码 / 结果包装 + WorkspacePathGuard（纯函数，TDD）
module: mcp
dependsOn: [TASK-MCP-00]
risk: medium
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='WorkspacePathGuardTest,EnvironmentToolSpecsTest' test
  - bash bin/gen-codemap.sh --check
  - git diff --check
---

# TASK-MCP-01：合约冻结 + 路径安全（Contract-first）

## 0. Meta
- 语言/框架版本：Java 17 / JUnit 5 / AssertJ；JSON 用项目现有 `com.agenteval.util.Jsons`
- 影响模块/包前缀：新包 `com.agenteval.mcp`；本卡**不改**任何既有类
- 最小验收命令：`mvn -q -Dtest='WorkspacePathGuardTest,EnvironmentToolSpecsTest' test`

## 1. 背景
- 现状：`docs/10` §2.4 给了工具面合约的表格，但没有可执行的单一事实源。
- 痛点：02（适配器）、03（传输）、04（工具实现）、06（红队）、07（文档）都要引用合约；不先落成代码 + 快照，五张卡会各写一份。

## 2. 目标（Definition of Done）
- [ ] DoD-1：`mcp/EnvironmentToolSpecs.java`：`public final class`，常量 `List<ToolSpec> ALL`（顺序固定：`get_task, workspace_list, workspace_read, workspace_write, call_tool, submit, get_feedback`）；`record ToolSpec(String name, String description, JsonNode inputSchema)`；`static JsonNode toolsListJson()` 输出 MCP `tools/list` 的 `tools` 数组
- [ ] DoD-2：`mcp/ToolError.java`：`enum ToolError { INVALID_PATH, NOT_FOUND, TOO_LARGE, WRONG_STATE, ALREADY_SUBMITTED, TOOL_NOT_ALLOWED, INVALID_ARGUMENT, INTERNAL }`，方法 `JsonNode toResult(String message)` 产出 §5 的错误包装
- [ ] DoD-3：`mcp/ToolResults.java`：`static JsonNode ok(JsonNode payload)` / `static JsonNode error(ToolError code, String message)`，按 §5 包装成 `CallToolResult`
- [ ] DoD-4：`mcp/WorkspacePathGuard.java`：`public final class`，构造 `WorkspacePathGuard(Path workspaceDir)`；`Resolved resolveForRead(String rel)`、`Resolved resolveForWrite(String rel)`，`record Resolved(String relative, Path absolute)`；失败抛 `PathViolation extends RuntimeException`（含 `ToolError code`）
- [ ] DoD-5：`mcp/Limits.java`：`WRITE_MAX_BYTES = 2 * 1024 * 1024`，`SUBMIT_MAX_BYTES = 1024 * 1024`，`READ_DEFAULT_LIMIT_BYTES = 256 * 1024`；预留 `static Limits fromRuntime(TaskSpec.RuntimeSpec)`（v1 返回默认值，不改 `TaskSpec`）
- [ ] DoD-6：快照测试 `EnvironmentToolSpecsTest`：`toolsListJson()` 与 `src/test/resources/mcp/tools-list.snapshot.json` 逐字节一致（pretty JSON）；改合约必须显式更新快照并在 PR 说明
- [ ] DoD-7：`WorkspacePathGuardTest` 覆盖 §9 全部场景
- [ ] DoD-8：`docs/10` §2.4 表格末尾加一句"代码事实源：`EnvironmentToolSpecs` + `tools-list.snapshot.json`"

## 3. 范围
### In-scope
- 上述 5 个新类 + 2 个测试 + 1 个快照资源；`docs/10` 一句话
### Out-of-scope
- 工具的**实现**（04）、传输（03）、适配器（02）
- `RulesJudge.existsInsideWorkspace`：**不合并**（判分器是宽口径，PathGuard 是严口径，语义不同）

## 4. 约束与关键决策
- 合约合入后视为冻结；后续卡片只能实现。变更流程：改 `EnvironmentToolSpecs` → 更新快照 → 同步 `docs/10` §2.4 → PR 说明。
- 工具 `description` 是 Agent 看到的使用说明，用中文，每条 ≤ 200 字，包含"何时用 / 参数含义 / 返回什么 / 常见错误"。
- 不依赖 SDK 类型（即使 00 选了 SDK）：合约类只产出 `JsonNode`，SDK 适配放在 03，保证合约层零依赖。

## 5. 合约与错误语义（本卡冻结的全部内容）

### 5.1 结果包装（所有工具统一）

成功：
```json
{"content":[{"type":"text","text":"<payload 的 JSON 字符串>"}],"structuredContent":<payload>,"isError":false}
```
失败：
```json
{"content":[{"type":"text","text":"{\"code\":\"INVALID_PATH\",\"message\":\"...\"}"}],"structuredContent":{"code":"INVALID_PATH","message":"..."},"isError":true}
```
JSON-RPC 级 error（`-32600/-32601/-32602/-32700`）只用于：非法帧、未知 method、`tools/call` 缺 `name`、未 `initialized` 就调用（03 实现）。

### 5.2 工具 inputSchema / 返回 payload

| 工具 | inputSchema（JSON Schema draft 2020-12） | 返回 payload |
| --- | --- | --- |
| `get_task` | `{"type":"object","properties":{},"additionalProperties":false}` | `{"task_id","task_name","attempt_id","attempt_number","max_attempts","state":"WAITING_SUBMIT\|SUBMITTED\|CLOSED","instructions":"<markdown>","submission_type","allowed_tools":[{"name","description"}]}` |
| `workspace_list` | `{"type":"object","properties":{"path":{"type":"string","default":"."}},"additionalProperties":false}` | `{"path","entries":[{"path","type":"file\|dir","size"}]}`（深度 1，按名排序，不含 `.ael`） |
| `workspace_read` | `{"type":"object","required":["path"],"properties":{"path":{"type":"string"},"offset":{"type":"integer","minimum":0,"default":0},"limit":{"type":"integer","minimum":1,"maximum":1048576}},"additionalProperties":false}` | `{"path","size","offset","content","encoding":"utf-8\|base64","truncated":bool}` |
| `workspace_write` | `{"type":"object","required":["path","content"],"properties":{"path":{"type":"string"},"content":{"type":"string"},"mode":{"type":"string","enum":["overwrite","append"],"default":"overwrite"}},"additionalProperties":false}` | `{"path","bytes","sha256"}` |
| `call_tool` | `{"type":"object","required":["name","input"],"properties":{"name":{"type":"string"},"input":{"type":"object"}},"additionalProperties":false}` | `{"call_id","success":bool,"output":<any>,"error":string\|null}` |
| `submit` | `{"type":"object","required":["submission"],"properties":{"submission":{"type":"object"}},"additionalProperties":false}` | 见 5.3 |
| `get_feedback` | `{"type":"object","properties":{"attempt_id":{"type":"string","pattern":"^attempt_[0-9]{3}$"}},"additionalProperties":false}` | feedback 文件的对外字段原样（`schema_version, attempt_id, valid, feedback, schema_errors?, failed_checks?, dimension_scores?, next_step, next_attempt_id?`） |

### 5.3 `submit` 返回 payload

```json
{
  "accepted": true,
  "pending": false,
  "attempt_id": "attempt_001",
  "valid": true,
  "score": 35.0,            // valid=false 时省略
  "passed": false,          // valid=false 时省略
  "feedback": "...",
  "failed_checks": [...],   // 与 feedback 文件同结构
  "schema_errors": [...],   // valid=false 时给出
  "next_step": "...",
  "next_attempt_id": "attempt_002",   // 终局轮省略
  "run_finished": false,
  "status": "PASSED"        // run_finished=true 时给出
}
```
`pending:true` 时只含 `accepted, pending, attempt_id`。

### 5.4 错误码使用矩阵

| 错误码 | 由哪些工具产生 |
| --- | --- |
| `INVALID_ARGUMENT` | 任何工具：入参不符 inputSchema |
| `INVALID_PATH` | workspace_*：绝对路径、`..`、控制字符、逃逸、写到符号链接、`.ael/` |
| `NOT_FOUND` | workspace_read/list：不存在；get_feedback：无该轮反馈 |
| `TOO_LARGE` | workspace_write > 2 MiB；submit > 1 MiB |
| `WRONG_STATE` | submit：当前不在 WAITING_SUBMIT；get_task 不报错只带 state |
| `ALREADY_SUBMITTED` | submit：本轮已提交 |
| `TOOL_NOT_ALLOWED` | call_tool：不在 `allowed_tools` |
| `INTERNAL` | 其他运行时异常（message 不含堆栈） |

## 6. Failure Modes & Safeguards（PathGuard 规则）
- 输入为空 / 含 `\u0000`–`\u001f` → `INVALID_PATH`
- `Path.of(rel).isAbsolute()` → `INVALID_PATH`
- `normalize()` 后任一段为 `..` → `INVALID_PATH`
- 相对路径首段为 `.ael` → `INVALID_PATH`
- 读：目标存在时 `toRealPath()` 必须以 `workspaceDir.toRealPath()` 为前缀（`startsWith`），否则 `INVALID_PATH`；不存在 → `NOT_FOUND`
- 写：目标若存在且 `Files.isSymbolicLink` → `INVALID_PATH`（不跟随）；目标不存在时，最近的已存在祖先目录 realpath 必须在 workspace 内
- 比较一律 realpath 对 realpath（大小写不敏感文件系统安全）

## 7. 可观测性 & 运维
- 无运行时组件

## 8. 代码改动点
- 文件清单：`src/main/java/com/agenteval/mcp/{EnvironmentToolSpecs,ToolError,ToolResults,WorkspacePathGuard,Limits}.java`；`src/test/java/com/agenteval/mcp/{EnvironmentToolSpecsTest,WorkspacePathGuardTest}.java`；`src/test/resources/mcp/tools-list.snapshot.json`；`docs/10`
- 配置项：无

## 9. 测试策略
- `WorkspacePathGuardTest`（每条一个 `@Test`，中文方法名）：
  1. 正常相对路径读写解析为 workspace 内绝对路径
  2. `./docs/a.md` 与 `docs/a.md` 等价
  3. `../hidden/x` → INVALID_PATH
  4. `docs/../../x` → INVALID_PATH
  5. 绝对路径 → INVALID_PATH
  6. 含 `\u0000` → INVALID_PATH
  7. `.ael/bin/agent-eval` → INVALID_PATH
  8. workspace 内符号链接指向外部文件：读 → INVALID_PATH
  9. workspace 内符号链接指向内部文件：读 → 允许（返回 realpath）
  10. 写到已存在的符号链接（无论指向哪）→ INVALID_PATH
  11. 写到不存在的 `sub/new.txt`（sub 存在）→ 允许；`nosuch/new.txt`（祖先不存在）→ 允许（创建目录由 04 负责，本卡只校验路径）
  12. 读不存在文件 → NOT_FOUND
- `EnvironmentToolSpecsTest`：快照一致；7 个工具名顺序；每个 inputSchema 含 `additionalProperties:false`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：两类测试全绿；快照文件入库；CODEMAP 重新生成

## 11. 风险与回退
- 风险：合约定得过细阻碍实现 → 只冻结 name / inputSchema / 返回顶层字段 / 错误码；`description` 文案允许改（快照需同步）
- 回退：纯新增，删除目录即可
