---
id: TASK-MCP-08
title: v2：Streamable HTTP 传输 + token 鉴权 + 一进程多会话（不阻塞 v1）
module: mcp / cli
dependsOn: [TASK-MCP-07]
risk: high
featureFlag: none
status: deferred
dodCommands:
  - mvn -q -Dtest='McpHttpTransportTest' test
---

# TASK-MCP-08：Streamable HTTP（v2）

## 1. 背景
- stdio 要求 client 与框架同机并由 client 拉起进程；企业 Agent 平台、跨机 Agent 需要 URL 接入。

## 2. 目标（Definition of Done）
- [ ] 在 `ToolGatewayServer` 同款 JDK `HttpServer` 上实现 Streamable HTTP（POST JSON-RPC；SSE 可选）；不引入 Servlet / Spring
- [ ] `agent-eval mcp-serve --transport http --port <p> --task <dir>`：每个会话（`Mcp-Session-Id`）对应一次 run；会话 token 在启动时打印到 stderr / 写文件，client 以 `Authorization: Bearer` 携带
- [ ] 会话超时、并发上限、run 目录按会话隔离
- [ ] 红队：无 token / 错 token / 跨会话 `submit` 均拒绝

## 3. 范围
### Out-of-scope
- OAuth / 企业 SSO；TLS 终结（交给反向代理）

## 4. 约束与关键决策
- 与 stdio 共用 `McpEnvironmentServer` 的分派层，只换传输；协议版本锁定与 v1 一致。

## 11. 风险与回退
- 协议 HTTP/auth 部分仍在演进；本卡开工前重新核对规范版本
