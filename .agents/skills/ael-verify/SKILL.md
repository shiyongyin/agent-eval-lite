---
name: ael-verify
description: 在 AgentEval-Lite 仓库改完代码/任务/红队/文档后，按改动范围选择最便宜的验证闸并执行。当用户要求"验证改动"、"跑测试"、"过门禁"、"提交前检查"时使用。
---

# AgentEval-Lite 验证闸选择

按下表选**能证明改动正确的最便宜组合**，从上往下第一条命中即用；提交/PR 前一律补跑最后一行。完整阶梯与语义见根 `AGENTS.md`「构建与验证阶梯」。

| 改动范围 | 验证命令 |
| --- | --- |
| 单个类/包的 Java 逻辑 | `mvn -q test -Dtest=<对应Test类>`（测试与被测包同路径，见 `docs/CODEMAP.md` 测试索引） |
| 跨包 Java 改动 / 不确定影响面 | `mvn -q test` |
| judge 规则引擎 / trace 签名 / nonce / canary / 工具网关 | `mvn -q test` **且** `bash redteam/test_gate.sh && bash redteam/run_all.sh`（安全敏感面必须过红队） |
| 某个任务（内置 `tasks/<id>` 或私有 `evalsets/<set>/tasks/<id>`） | `bin/agent-eval validate --task <task-dir>` → `bin/agent-eval run --task <task-dir> --agent scripted --script <task-dir>/samples/replay.yaml`（私有集回放加 `--runs-root evalsets/<set>/runs`） |
| 内置任务库整体 / SubmissionManager / 反馈链路 | `bin/agent-eval suite --tasks-root tasks --fail-on-not-passed` |
| 红队脚本或门禁逻辑 | `bash redteam/test_gate.sh && bash redteam/run_all.sh` |
| CLI 命令面 | `mvn -q package` 后手跑 `bin/agent-eval <cmd> --help` + 一条真实路径 |
| 提交/PR 前 | `bash bin/ci-smoke.sh`（快路径：`SKIP_TESTS=1 bash bin/ci-smoke.sh`，仅当本轮已跑过 `mvn -q test`） |

## 必须知道的坑

- **CODEMAP 漂移门禁**：增删类、改类 Javadoc 首句、增删 CLI 子命令/check 类型/trace 事件/内置任务后，必须 `bash bin/gen-codemap.sh` 并提交 `docs/CODEMAP.md`，否则 ci-smoke 第 0 道闸失败。CODEMAP 只索引内置 `tasks/`，私有 `evalsets/` 任务的增删不触发。
- `bin/agent-eval` 与 redteam 依赖已构建的 `target/agent-eval-lite-*-cli.jar`；缺失先 `mvn -q -DskipTests package`（redteam 脚本会自动补建）。
- Docker 相关端到端用例在无 Docker 环境自动跳过，不算失败；红队基线随 Docker 就绪度自适应（就绪 0 / 回退 1），语义见 `redteam/AGENTS.md`。
- 退出码：`suite`/`run` 配 `--fail-on-not-passed` 时未通过为 3；2 是框架故障——遇 2 先查框架/环境而不是改任务。
