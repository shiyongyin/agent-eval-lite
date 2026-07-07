# demo-ops-agent：私有测评集完整示例

`ael-build-evalset` skill 工作流的可运行产物，演示「建自己的测评集 → 接入自己的 Agent → 批跑度量」全链路。私有测评集独立于内置 `tasks/`（那是框架自测库，会进 CI 门禁），所有命令显式带 `--tasks-root` / `--runs-root`。

## 内容

| 目录 | 内容 |
| --- | --- |
| `tasks/log-triage-001` | 日志错误分诊（smoke）：材料埋了「消息含 error 的 INFO 行」「含错误码的 WARN 行」两个陷阱，只会全文 grep 的 Agent 会数错 |
| `tasks/config-audit-001` | 配置安全基线审计（regression）：跨文件对照 `app.yaml` 与六条基线规则，找全违规、不误报、按基线级别挑最该先修项 |
| `agents/smart_ops_agent.sh` | 示例被评 Agent（规则版）：按任务真实推导答案，演示自研 CLI Agent 的接入契约（读 `AEL_*` 环境变量、写 `$AEL_INBOX/$AEL_ATTEMPT_ID.json`） |
| `agents/naive_ops_agent.sh` | 示例被评 Agent（朴素版）：保留真实 Agent 的常见毛病（全文 grep、不读反馈），用于对比面板出现区分度 |
| `agents.yaml` | 三方对比清单：scripted 基线 + smart + naive |
| `runs/` | 评估产物（已 .gitignore，不入库） |

## 标准命令（逐条可跑）

```bash
# 任务自测：静态体检 + fail→pass 回放闭环
bin/agent-eval validate --task evalsets/demo-ops-agent/tasks/log-triage-001
bin/agent-eval run --task evalsets/demo-ops-agent/tasks/log-triage-001 --agent scripted \
    --script evalsets/demo-ops-agent/tasks/log-triage-001/samples/replay.yaml \
    --runs-root evalsets/demo-ops-agent/runs

# 单任务接入 cli Agent 冒烟（$PWD 锚定脚本绝对路径；Agent 进程 cwd 是 workspace）
bin/agent-eval run --task evalsets/demo-ops-agent/tasks/log-triage-001 --agent cli \
    --cmd "bash $PWD/evalsets/demo-ops-agent/agents/smart_ops_agent.sh" \
    --model rule-based-demo --runs-root evalsets/demo-ops-agent/runs

# 多 Agent 对比批跑（任务 × Agent 矩阵 + 排名面板）
bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks \
    --runs-root evalsets/demo-ops-agent/runs --agents-file evalsets/demo-ops-agent/agents.yaml

# 单 Agent pass^k 可靠性（k 次全过才算稳定通过）
bin/agent-eval suite --tasks-root evalsets/demo-ops-agent/tasks \
    --runs-root evalsets/demo-ops-agent/runs \
    --agent cli --cmd 'bash "$AEL_RUN_DIR/../../../agents/smart_ops_agent.sh"' \
    --label smart-ops --repeat 2

# 跨 run 历史趋势
bin/agent-eval history --runs-root evalsets/demo-ops-agent/runs
```

预期结果：scripted 基线与 smart-ops 全过（smart 首轮 100 分），naive-ops 两个任务全挂（日志任务 60 分——数错行数；审计任务 15 分——漏报明文凭证且不对照基线级别，且它不读反馈所以三轮不涨分）。这正是任务「有区分度」的样子。

## 拿它当模板

复制目录骨架后按 `.agents/skills/ael-build-evalset/SKILL.md` 的五阶段工作流替换内容；任务红线见 `tasks/AGENTS.md`（hidden 防泄露检查单同样适用于私有集）。
