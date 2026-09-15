---
id: TASK-USE-02
title: run-agent.sh 内置 claude/codex/custom 预设并注入上一轮反馈
module: evalsets/_template / docs
dependsOn: []
risk: medium
status: done
featureFlag: none
dodCommands:
  - bash -n evalsets/_template/scripts/run-agent.sh
  - mvn -q -Dtest=EvalsetInitScaffoldTest test
  - bin/agent-eval run --task evalsets/demo-ops-agent/tasks/log-triage-001 --agent cli --cmd 'bash evalsets/_template/scripts/run-agent.sh claude' --runs-root runs/dogfood
---

# TASK-USE-02：run-agent.sh 内置 claude/codex/custom 预设并注入上一轮反馈

## 0. Meta
- 语言/框架版本：bash（需 `jq`）；Java 17 测试 `EvalsetInitScaffoldTest`
- 影响模块/包前缀：`evalsets/_template/`、`docs/06`、`src/test/java/com/agenteval/cli/EvalsetInitScaffoldTest.java`
- 最小验收命令：`bash -n` + `EvalsetInitScaffoldTest`

## 1. 背景
- 现状：`evalsets/_template/scripts/run-agent.sh` 打印“尚未接入”后 `exit 1`；`agents.yaml` 的 current / candidate 都指向它。`CliAgentAdapter` 已注入 `AEL_INSTRUCTIONS` / `AEL_INBOX` / `AEL_ATTEMPT_ID` / `AEL_FEEDBACK`（上一轮 feedback 文件路径，首轮为空串）。
- 痛点：每个团队要自己摸索如何把 `claude -p` / `codex exec` 接进来；对每轮新起进程的 Agent，`instructions.md` 只说反馈写在某目录，Agent 未必去读，多轮修正失效。

## 2. 目标（Definition of Done）
- [ ] `run-agent.sh <profile>` 支持 `claude` / `codex` / `custom`（custom 报错并指出改哪几行）
- [ ] 第 2 轮起 prompt 含“上一轮评审反馈”段（仅取 feedback JSON 的 `feedback` / `failed_checks` / `next_step`）
- [ ] prompt 明确本轮提交文件名 `$AEL_INBOX/$AEL_ATTEMPT_ID.json`
- [ ] 模型 / 额外参数经 `AEL_AGENT_MODEL`、`AEL_AGENT_EXTRA_ARGS` 覆盖，profile 内不写死
- [ ] 模板 `agents.yaml` 改为 `baseline-scripted` / `current: claude` / `candidate: codex` 示例并注释替换方法
- [ ] `EvalsetInitScaffoldTest` 断言生成的脚本含三个 profile 分支
- [ ] `docs/06` “接入 Agent”一节与 `evalsets/_template/README.md` 更新

## 3. 范围
### In-scope
- `evalsets/_template/scripts/run-agent.sh`、`evalsets/_template/agents.yaml`、`evalsets/_template/README.md`
- `docs/06-小团队落地指南.md`
- `src/test/java/com/agenteval/cli/EvalsetInitScaffoldTest.java`
### Out-of-scope
- `CliAgentAdapter` 与任何 Java 主代码
- 新增 CLI 选项

## 4. 约束与关键决策
- 脚本只读 feedback 的对外字段；禁止读取 `judge/`、`hidden/`、`traces/`。
- 模型调用失败 → 非零退出，让框架记该轮无提交；不吞错、不伪造提交。
- profile 内只放已在 TASK-USE-01 验证过的最小命令。

## 5. 合约与错误语义
- 输入：`$1` profile；环境变量 `AEL_*`（框架注入）、`AEL_AGENT_MODEL` / `AEL_AGENT_EXTRA_ARGS`（可选）
- 输出：`$AEL_INBOX/$AEL_ATTEMPT_ID.json`（由 Agent 写）
- 退出码：Agent 命令退出码透传；profile 非法 → 2

## 6. Failure Modes & Safeguards
- `jq` 缺失 → 明确报错退出，不降级为空 prompt
- `AEL_FEEDBACK` 指向的文件不存在 → 视为首轮，仅打印 warning
- Agent CLI 未安装 → `command -v` 检查，报错并提示 TASK-USE-03 镜像

## 8. 代码改动点
- 文件清单：见 In-scope
- 配置项：`AEL_AGENT_MODEL`、`AEL_AGENT_EXTRA_ARGS`

## 9. 测试策略
- 最小集：`bash -n`；`EvalsetInitScaffoldTest`
- 推荐回归：`shellcheck`（若本机有）；对 `demo-ops-agent` 单任务真实跑一次

## 10. 验收标准
- 命令：`bin/agent-eval evalset init --id tmp-x --tasks-root /tmp` 后 `rg "claude\)|codex\)|custom\)" <生成脚本>`
- 期望结果：三个分支均命中；真实跑一次能在 inbox 产生提交

## 11. 风险与回退
- 风险：绑定特定 CLI 版本参数 → 参数走环境变量覆盖
- 回退：恢复原 `exit 1` 桩
