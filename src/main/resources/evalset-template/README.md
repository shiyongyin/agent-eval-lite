# __EVALSET_ID__ 私有测评集

这是一个企业内部 AI Agent 私有测评集骨架。任务资产放在本目录下，运行产物放
`runs/`（已被根 `.gitignore` 忽略），不要把业务任务混进仓库内置 `tasks/`。

## 目录约定

| 路径 | 用途 |
| --- | --- |
| `tasks/` | 私有任务库；每个任务结构同内置任务：`task.yaml` + `work/` + `hidden/` + `samples/` |
| `agents.yaml` | 多 Agent 横评清单；默认含 scripted 基线、current（claude）与 candidate（codex） |
| `scripts/run-agent.sh` | 团队 Agent 接入包装器；内置 claude / codex / custom profile，统一组装 prompt、注入上一轮反馈 |
| `runs/` | 评测产物；不入库 |

## 从第一个任务开始

```bash
bin/agent-eval task init --id first-task-001 --tasks-root evalsets/__EVALSET_ID__/tasks

bin/agent-eval validate --task evalsets/__EVALSET_ID__/tasks/first-task-001

bin/agent-eval run --task evalsets/__EVALSET_ID__/tasks/first-task-001 \
    --agent scripted \
    --script evalsets/__EVALSET_ID__/tasks/first-task-001/samples/replay.yaml \
    --runs-root evalsets/__EVALSET_ID__/runs
```

## 接入真实 Agent

`scripts/run-agent.sh` 内置 `claude` / `codex` / `custom` 三个 profile（第一个参数），
会把题面、本轮提交文件名和上一轮反馈拼成 prompt 交给 Agent；模型与额外参数用
`AEL_AGENT_MODEL` / `AEL_AGENT_EXTRA_ARGS` 覆盖。接自研 Agent 时只改 `run_custom()`，
契约只有一条：结束前把提交写到 `$AEL_INBOX/$AEL_ATTEMPT_ID.json`。单任务冒烟：

```bash
bin/agent-eval run --task evalsets/__EVALSET_ID__/tasks/first-task-001 \
    --agent cli \
    --cmd 'bash "$AEL_RUN_DIR/../../../scripts/run-agent.sh" claude' \
    --runs-root evalsets/__EVALSET_ID__/runs
```

多 Agent 横评：

```bash
bin/agent-eval suite --tasks-root evalsets/__EVALSET_ID__/tasks \
    --runs-root evalsets/__EVALSET_ID__/runs \
    --agents-file evalsets/__EVALSET_ID__/agents.yaml \
    --repeat 3
```

## 小团队推荐分层

- `smoke`：5-10 个任务，每次改 Agent / prompt 必跑。
- `regression`：20-50 个任务，合并前或发版前跑。
- `domain`：业务专项任务，不一定进硬门禁。
- `security`：工具权限、越权、泄露类任务。

任务质量标准见仓库根 `docs/07-任务质量清单.md`。
