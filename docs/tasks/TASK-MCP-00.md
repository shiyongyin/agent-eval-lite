---
id: TASK-MCP-00
title: Spike：MCP Java SDK vs 手写 stdio JSON-RPC 定案 + 真实 client 握手（时间盒 1 天）
module: mcp（spike，不进主干）
dependsOn: []
risk: medium
featureFlag: none
status: todo
dodCommands:
  - mvn -q -DskipTests package
  - bash /tmp/mcp-spike/handshake.sh
  - rg -n "^### 决策" docs/10-MCP环境适配器设计.md
---

# TASK-MCP-00：Spike——依赖方案定案 + 真实 client 握手

## 0. Meta
- 语言/框架版本：Java 17 / Maven 3.9；候选依赖 `io.modelcontextprotocol.sdk:mcp`（查 Maven Central 最新 0.1x；含 `StdioServerTransportProvider`）
- 影响模块/包前缀：`src/main/java/com/agenteval/mcp/spike/`（spike 结束后**删除**整个目录，只留决策记录）
- 最小验收命令：`bash /tmp/mcp-spike/handshake.sh`（脚本由本卡步骤 3 生成，不入库）

## 1. 背景
- 现状：项目零外部服务、单 jar（`target/agent-eval-lite-*-cli.jar`）、Jackson 2.x（`pom.xml` `jackson.version`）、日志 slf4j-simple、HTTP 用 JDK `com.sun.net.httpserver`（`tool/ToolGatewayServer.java`）。
- 痛点：后面 7 张卡的写法取决于"用 SDK 还是手写"；必须先定，且要用**真实 client** 握手，不能只靠单测。

## 2. 目标（Definition of Done）
- [ ] DoD-1：在 `mcp/spike/SpikeServer.java` 用候选 SDK 起一个 stdio server，只注册一个工具 `ping`（无参，返回 `{"pong":true}`）
- [ ] DoD-2：握手脚本 `/tmp/mcp-spike/handshake.sh` 用原始 JSON-RPC（`printf` + `java -jar`）发 `initialize` → `notifications/initialized` → `tools/list` → `tools/call ping`，四步响应都是合法单行 JSON 且 `tools/list` 含 `ping`
- [ ] DoD-3：Codex CLI 配置 `mcp_servers.spike`（`command=java`, `args=[-jar, <jar>, mcp-spike]`）后，`codex exec '列出你可用的工具名'` 的输出含 `ping`；记录 Codex 实际协商的 `protocolVersion`
- [ ] DoD-4：官方 SDK **client**（test scope）与 SpikeServer 握手通过（`McpSpikeHandshakeTest`，spike 结束后随目录删除）
- [ ] DoD-5：测量并记录：fat jar 体积增量（`ls -l target/*-cli.jar` 前后）、传递依赖清单（`mvn dependency:tree -Dincludes=io.modelcontextprotocol.sdk,io.projectreactor`）、Jackson 2 兼容方式（是否需要 `mcp-json-jackson2` 模块或降版本）
- [ ] DoD-6：实测 Codex 对长时工具调用的行为：`ping` 处理器里 `Thread.sleep(45_000)`，记录 client 是等待、超时还是取消（`notifications/cancelled`），得出 `submit` 的 `pending` 阈值（秒）
- [ ] DoD-7：在 `docs/10` §2.6 末尾新增 `### 决策` 小节：方案（SDK / 手写）、理由、jar 增量、Jackson 方案、pending 阈值、Codex 协商版本；`docs/10` §3.1 的 pending 阈值同步
- [ ] DoD-8：删除 `mcp/spike/` 与临时 `pom.xml` 改动（若定案手写）或把依赖改为正式坐标（若定案 SDK）；`mvn -q verify` 全绿

## 3. 范围
### In-scope
- `pom.xml`（临时或正式依赖）、`mcp/spike/*`、`cli/Main` 临时注册 `mcp-spike` 子命令（结束后移除）、`docs/10` §2.6 / §3.1
### Out-of-scope
- 任何正式工具实现、`RunManager` / `AgentAdapter` 改动、文档以外的其他 docs

## 4. 约束与关键决策
- **判据（三条都满足才选 SDK）**：(a) 在 Jackson 2.x 下编译运行；(b) fat jar 增量 < 5 MB；(c) DoD-3 与 DoD-4 两类 client 握手通过。任一不满足 → 手写 stdio JSON-RPC 子集：`initialize` / `notifications/initialized` / `tools/list` / `tools/call` / `ping`；其他 method 返回 JSON-RPC error `-32601`；notifications 忽略。
- 无论产物用哪种，**测试**都用官方 SDK client（`<scope>test</scope>`）做握手回归。
- 时间盒 1 个工作日；到点未定案即按手写方案走，并在 `### 决策` 里写明"因时间盒"。

## 5. 合约与错误语义
- 本卡不定义正式合约；`ping` 只是探针。

## 6. Failure Modes & Safeguards
- SDK 与 Jackson 3 强绑定 → 先试 `mcp-json-jackson2` 模块 / 降到最后一个 Jackson 2 版本；都不行即手写。
- Codex 加载失败 → 检查 stdout 是否被日志污染（`2>/tmp/spike.err` 对比）、jar 路径是否绝对；把发现写进 `docs/10` §3.5。
- `Thread.sleep(45_000)` 期间 Codex 断开 → 说明 client 超时 < 45 s，pending 阈值取 client 超时的 2/3。

## 7. 可观测性 & 运维
- 排障路径：`/tmp/spike.err`（server stderr）、`~/.codex/log/`（Codex 日志）

## 8. 代码改动点
- 文件清单：`pom.xml`、`src/main/java/com/agenteval/mcp/spike/SpikeServer.java`、`src/main/java/com/agenteval/cli/McpSpikeCommand.java`（临时）、`src/test/java/com/agenteval/mcp/McpSpikeHandshakeTest.java`（临时）、`docs/10-MCP环境适配器设计.md`
- 配置项：`~/.codex/config.toml` 的 `[mcp_servers.spike]`（本机，不入库）

## 9. 测试策略
- 最小集：`handshake.sh`（原始帧）+ `McpSpikeHandshakeTest`（SDK client）
- 推荐回归：DoD-8 后 `mvn -q verify`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：握手脚本四步全部合法 JSON；`docs/10` 有 `### 决策`；spike 目录已删除；`git status` 只剩 `pom.xml`（若 SDK）与 `docs/10`

## 11. 风险与回退
- 风险：spike 结论被后卡质疑 → 决策段写明判据数据，可复算
- 回退：`git checkout pom.xml`，删除 spike 目录
