---
id: TASK-MCP-01
title: 合约冻结：7 个工具 schema / 错误码 / 留痕字段 + WorkspacePathGuard（纯函数，TDD）
module: mcp / util
dependsOn: [TASK-MCP-00]
risk: medium
featureFlag: none
status: todo
dodCommands:
  - mvn -q -Dtest='WorkspacePathGuardTest,EnvironmentToolsContractTest' test
  - bash bin/gen-codemap.sh --check
---

# TASK-MCP-01：合约冻结 + 路径安全（Contract-first）

## 0. Meta
- 语言/框架版本：Java 17 / JUnit 5
- 影响模块/包前缀：`com.agenteval.mcp`（`EnvironmentToolSpecs`、`WorkspacePathGuard`）、`com.agenteval.util` 或 `workspace`（路径判定共用）
- 最小验收命令：`mvn -q -Dtest='WorkspacePathGuardTest,EnvironmentToolsContractTest' test`

## 1. 背景
- 现状：`docs/10` §2.4 定义了 7 个工具的合约；`RulesJudge.existsInsideWorkspace` 已有一份"路径在 workspace 内"的判定。
- 痛点：后续服务器、适配器、红队、文档都依赖合约；不先冻结，每张卡都会各改一点。

## 2. 目标（Definition of Done）
- [ ] `EnvironmentToolSpecs`：7 个工具的 name / description / inputSchema（JSON Schema）/ 错误码枚举，作为唯一事实源；`tools/list` 与文档表格都从它生成
- [ ] 合约快照测试：`EnvironmentToolsContractTest` 把 `tools/list` 结果序列化后与 `src/test/resources/mcp/tools-list.snapshot.json` 比对，改合约必须显式更新快照
- [ ] `WorkspacePathGuard.resolveForRead / resolveForWrite`：拒绝绝对路径、`..`、控制字符、realpath 逃逸、`.ael/` 前缀；读：指向外部的符号链接拒绝；写：目标为符号链接（无论指向哪）一律拒绝，父目录按 realpath 校验（`Files.write` 默认跟随链接）；返回规范化相对路径 + 绝对路径
- [ ] **不**与 `RulesJudge.existsInsideWorkspace` 合并：判分器是宽口径（跟随符号链接、前缀判断），PathGuard 是严口径（realpath、拒符号链接）；两者各自保留，测试各自钉死
- [ ] 大小上限常量与 `runtime` 覆盖点定义（默认 write 2 MiB / submit 1 MiB）
- [ ] 工具结果包装规范：`CallToolResult.content=[{type:text,text:<JSON>}]`（+ `structuredContent`），错误 `isError:true` 且载荷 `{code,message}`；JSON-RPC error 只用于协议级错误

## 3. 范围
### In-scope
- 上述新类与测试（无 `RulesJudge` 改动）
### Out-of-scope
- 传输层、适配器、RunManager

## 4. 约束与关键决策
- 合约一旦合入，后续卡片只能实现；变更回到本卡走快照更新 + `docs/10` §2.4 同步。
- 错误返回统一为 `isError:true` + `{code, message}`，code 枚举：`INVALID_PATH`、`NOT_FOUND`、`TOO_LARGE`、`WRONG_STATE`、`ALREADY_SUBMITTED`、`TOOL_NOT_ALLOWED`、`INTERNAL`。

## 5. 合约与错误语义
- 见 `docs/10` §2.4；本卡把它落成代码与快照。

## 6. Failure Modes & Safeguards
- 符号链接：读写前 `toRealPath()`，不存在的写目标取父目录 realpath；指向外部一律 `INVALID_PATH`
- 大小写不敏感文件系统（macOS）：比较用 realpath，不做字符串前缀比较
- Unicode：路径先 `normalize()`，不做 NFC/NFD 转换（保持与文件系统一致），但拒绝控制字符

## 8. 代码改动点
- 文件清单：`mcp/EnvironmentToolSpecs.java`、`mcp/WorkspacePathGuard.java`、测试两类、快照资源

## 9. 测试策略
- 最小集：`WorkspacePathGuardTest`（正常 / `..` / 绝对 / 控制字符 / 读外指链接 / 读内指链接 / 写到链接 / 不存在写目标 / `.ael`）、`EnvironmentToolsContractTest`（快照）
- 推荐回归：无（纯新增）

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全绿；`docs/CODEMAP.md` 重新生成

## 11. 风险与回退
- 风险：合约定得过细 → 只冻结 name / 入参 / 返回顶层字段 / 错误码，描述文案允许微调
- 回退：纯新增代码，删除即可
