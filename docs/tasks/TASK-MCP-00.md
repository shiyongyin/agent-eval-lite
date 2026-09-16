---
id: TASK-MCP-00
title: Spike：MCP Java SDK vs 手写 stdio JSON-RPC 定案 + 真实 client 握手
module: mcp（spike，不进主干）
dependsOn: []
risk: medium
featureFlag: none
status: todo
dodCommands:
  - mvn -q -DskipTests package
  - "python3 - <<'EOF'\nimport json,subprocess,sys\np=subprocess.Popen(['java','-jar']+__import__('glob').glob('target/agent-eval-lite-*-cli.jar')+['mcp-serve','--task','tasks/api-payload-001','--runs-root','/tmp/mcp-spike'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)\np.stdin.write(json.dumps({'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'spike','version':'0'}}})+'\\n');p.stdin.flush()\nprint(p.stdout.readline()[:200]);p.kill()\nEOF"
---

# TASK-MCP-00：Spike——依赖方案定案 + 真实 client 握手

## 0. Meta
- 语言/框架版本：Java 17 / Maven；候选依赖 `io.modelcontextprotocol.sdk:mcp`（0.10.x，含 stdio transport）
- 影响模块/包前缀：临时分支或 `src/main/java/com/agenteval/mcp/spike`（定案后删除或改造为正式实现）
- 最小验收命令：见 `dodCommands`（能收到 `initialize` 响应即握手成功）

## 1. 背景
- 现状：项目零外部服务、单 jar、Jackson 2.x、JDK `HttpServer`；官方 MCP Java SDK 新版默认 Jackson 3 并引入 Reactor。
- 痛点：依赖选择会影响后面 6 张卡的写法，必须先定，不能边做边换。

## 2. 目标（Definition of Done）
- [ ] 用官方 SDK 起一个只含 `ping` 工具的 stdio server，能被（a）官方 SDK client（b）Codex CLI `mcp_servers` 配置 完成 initialize + tools/list
- [ ] 记录：fat jar 体积增量、Jackson 2 兼容方式、传递依赖清单、SDK 对工具超时 / 取消的处理方式
- [ ] 按 `docs/10` §2.6 判据给出决策（SDK / 手写），写回 `docs/10` §2.6 末尾"决策"段，并说明理由
- [ ] 实测 Codex（以及有条件时 Claude Desktop）对长时工具调用的默认超时，写进 `docs/10` §3.1 的 `pending` 阈值

## 3. 范围
### In-scope
- `pom.xml` 临时加依赖做测量；spike 代码
### Out-of-scope
- 任何正式工具实现、RunManager 改动

## 4. 约束与关键决策
- 判据：SDK 在 Jackson 2.x 下可编译；fat jar 增量 < 5 MB；两类 client 握手通过 → 采用 SDK；任一不满足 → 手写 stdio JSON-RPC 子集（initialize / tools/list / tools/call / ping / notifications 忽略）。
- 无论产物用哪种，**测试依赖**都用官方 SDK client 做握手回归（test scope）。

## 6. Failure Modes & Safeguards
- SDK 版本与 Jackson 3 强绑定 → 尝试 `mcp-json-jackson2` 模块或降版本；都不行即手写。
- Codex 不加载 server（配置路径、stdio 日志污染）→ 用 `--log-denials` / stderr 排查，把发现写进 §3.5。

## 8. 代码改动点
- 文件清单：`pom.xml`（临时）、spike 类、`docs/10` §2.6 / §3.1 回写

## 9. 测试策略
- 最小集：握手脚本（`dodCommands`）
- 推荐回归：无

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：stdout 第一行为合法 JSON-RPC `initialize` 响应；`docs/10` 有"决策"段

## 11. 风险与回退
- 风险：spike 拖长 → 时间盒 1 天，到点按手写方案走
- 回退：删除 spike 代码与临时依赖
