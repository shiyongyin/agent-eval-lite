# Contributing

AgentEval-Lite 是本地 AI Agent 评估框架。贡献前请先读 [AGENTS.md](AGENTS.md)，它是仓库协作、分区路由和验证门禁的权威入口。

## 开发环境

- Java 17+
- Maven 3.9+
- Docker 可选，仅 `--sandbox docker` 与强制 Docker 红队门禁需要

常用验证：

```bash
mvn -q test
mvn -q package
bin/agent-eval suite --tasks-root tasks --fail-on-not-passed
bash bin/ci-smoke.sh
```

## 贡献规则

- 新增或修改 Java 类时，保持包依赖方向：`cli -> runner -> {agent, judge, submission, tool, trace, report, state, workspace} -> task -> util`。
- 新增类必须写类级 Javadoc 首句；增删类、CLI 子命令、check 类型、trace 事件或任务后，运行 `bash bin/gen-codemap.sh`。
- 修改 `tasks/` 时不要把 `hidden/expected`、judge 规则细节或 mock 应答泄露到 `work/`、`samples/`、instructions、feedback 或 PR 描述。
- 用户私有测评集放在 `evalsets/<set>/`，不要混入内置 `tasks/`。
- 不提交 `target/`、`runs/`、本地 IDE 配置、真实凭证或真实客户数据。

## Pull Request 清单

- 说明变更目的和影响范围。
- 列出已运行的验证命令。
- 如果改了任务、schema、红队或 trace/judge/tool 可信链，说明对应回归结果。
- 如果引入新依赖，说明原因、许可证和替代方案。

提交贡献即表示你同意将贡献按本仓库的 Apache License 2.0 授权。
