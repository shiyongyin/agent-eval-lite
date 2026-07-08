# 私有测评集模板

复制本目录，或直接使用：

```bash
bin/agent-eval evalset init --id my-agent
```

私有测评集用于承载团队自己的 Agent 测试资产，独立于仓库内置 `tasks/`。内置 `tasks/` 是框架自测库，会进 CI 门禁；业务题库应放在 `evalsets/<set>/tasks/`。

## 给 AI 助手的说明

如果有 AI 辅助，请优先让 AI 使用仓库内置 skills 和本模板：

- `ael-build-evalset`：从本模板或 `evalset init` 起步，搭建私有测评集并接入 Agent。
- `ael-new-task`：在本测评集的 `tasks/`（即 `evalsets/<set>/tasks/`）下生成和修改任务脚手架，含判分规则编写与快速迭代。
- `ael-review-task-quality`：任务进入 smoke/regression 前做质量审查。
- `ael-verify`：改完后选择最小验证门禁。

不要让 AI 从空目录手写任务结构；应先生成脚手架，再替换业务材料、hidden 规则和样例闭环。

## 目录约定

| 路径 | 用途 |
| --- | --- |
| `tasks/` | 私有任务库；每个任务结构同内置任务 |
| `agents.yaml` | 多 Agent 横评清单 |
| `scripts/run-agent.sh` | 团队 Agent 接入包装器 |
| `runs/` | 评测产物；不入库 |

## 标准命令

```bash
bin/agent-eval task init --id first-task-001 --tasks-root evalsets/my-agent/tasks

bin/agent-eval validate --task evalsets/my-agent/tasks/first-task-001

bin/agent-eval run --task evalsets/my-agent/tasks/first-task-001 \
    --agent scripted \
    --script evalsets/my-agent/tasks/first-task-001/samples/replay.yaml \
    --runs-root evalsets/my-agent/runs

bin/agent-eval suite --tasks-root evalsets/my-agent/tasks \
    --runs-root evalsets/my-agent/runs \
    --agents-file evalsets/my-agent/agents.yaml \
    --repeat 3
```

任务质量标准见 [docs/07-任务质量清单.md](../../docs/07-任务质量清单.md)。
