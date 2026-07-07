---
name: ael-build-evalset
description: AI 辅助用户从零搭建自己的 AgentEval-Lite 测评集并接入真实 Agent 实跑。当用户说"建我自己的测评集"、"评估我的 Agent"、"接入我的 Agent 跑评测"、"把业务场景变成评估任务"时使用。
---

# 从零建测评集并接入你的 Agent

你（AI）在这个工作流里是「评估工程师」：先访谈把模糊诉求变成可判分的任务设计，再代用户生成任务、接入 Agent、实跑度量。权威规范：`tasks/AGENTS.md`（任务红线）、`docs/PLAYBOOK.md` recipe 4、根 `README.md`（check 类型语义）。**可运行的完整参照**：`evalsets/demo-ops-agent/`（2 个任务 + 2 个示例 cli Agent + agents.yaml 对比面板，README 里的命令逐条可跑）。

**布局铁律**：用户私有测评集放独立根目录（推荐 `evalsets/<set-name>/`），不要放进内置 `tasks/`——那是框架自测库，会进 CI 门禁。所有命令显式带 `--tasks-root` 与 `--runs-root`。

```text
evalsets/<set-name>/
├── tasks/<task-id>/     # 任务集（结构同内置任务：task.yaml + work/ + hidden/ + samples/）
├── agents/              # 被评 Agent 的接入物（脚本/包装器，可选）
├── agents.yaml          # 多 Agent 对比清单（可选）
└── runs/                # 评估产物（勿入库，.gitignore 已配 evalsets/*/runs/）
```

## 阶段 0：访谈（先问清楚再动手，一次问 3-5 个问题）

1. **评什么能力**：让用户给 1-3 个真实工作样本（工单/文档/代码/日志）。没有样本的评估设计都是空中楼阁。
2. **答案能否确定性判分**：期望结论能写成 JSON 精确值？能列成关键点清单？能用命令验证？三者都不行才考虑 `llm_rubric`（低权重主观维度专用）。
3. **任务形态映射**：修代码→`code_fix`；生成结构化数据→`api_payload`；读材料提取结论→`document`/`generic`；必须真调工具→`tool_call`；评审给意见→`review`。
4. **被评 Agent 形态**：命令行（claude/codex/自研 CLI）→ `cli`；HTTP 服务→`http`；不可信/强对抗→`cli + --sandbox docker`。
5. **规模与预算**：几个任务、每任务几轮（`max_attempts`）、要不要 `--repeat k` 可靠性口径。

## 阶段 1：逐任务生成（每个任务都走完这五步再做下一个）

```bash
bin/agent-eval task init --id <task-id> --tasks-root evalsets/<set>/tasks   # 从能跑的脚手架开始
```

1. 改 `task.yaml`：`agent_brief` 只描述任务与提交要求（禁夹带答案线索）；维度权重和 = `max_score`；`tier` 归类。
2. 放真实材料进 `work/`；`visible_context` 列出（带 `work/` 前缀）。**好任务要有区分度**：在材料里布设「朴素做法会踩中的陷阱」（如干扰行、跨文件才能推出的结论），让能力差的 Agent 真的拿不到分——全员满分的任务度量不出任何东西。
3. 写 `hidden/judge.rules.yaml`：期望值放 `hidden/expected/` 用 `expected_from` 引用；优先 `jsonpath_equals`（精确值）+ `list_coverage`（关键点覆盖，部分得分）；`feedback_fail` 只说哪里不对、禁含期望值。
4. 重写 `samples/` 三件套：fail 样例要犯「真实 Agent 最可能犯的错」，replay.yaml 编排 fail→pass。
5. 验证闭环（全绿才算一个任务完成）：

```bash
bin/agent-eval validate --task evalsets/<set>/tasks/<task-id>
bin/agent-eval run --task evalsets/<set>/tasks/<task-id> --agent scripted \
    --script evalsets/<set>/tasks/<task-id>/samples/replay.yaml --runs-root evalsets/<set>/runs
```

设计任务内容时逐条过 `tasks/AGENTS.md` 的 hidden 防泄露检查单。

## 阶段 2：接入用户的真实 Agent

**提交契约速查**（真实 Agent 最常见的挂法是提交文件不合法，先把这个讲给 Agent/用户）：

- 唯一有效通道：把 JSON 写进 `$AEL_INBOX/$AEL_ATTEMPT_ID.json`（如 `inbox/attempt_001.json`）；自然语言输出不计分。
- 信封必填：`schema_version:1`、`task_id`（=任务 id）、`attempt_id`（`attempt_NNN`，与文件名一致）、`submission_type`（与 task.yaml 的 schema 分型一致）、`summary`（≥8 字符）、`known_risks:[]`、`needs_human_review:false`；分型字段另加（`generic` 要求 `answer` 为 object）。
- 这些契约已渲染进 `instructions.md`——**cli Agent 的命令模板务必把 instructions 全文喂给 Agent**。

**cli 接入**（占位符自动 shell 转义；进程 cwd=workspace，超时强杀；stdout 落 `agent-logs/`）：

```bash
# LLM CLI（claude 示例；codex 等同理）
bin/agent-eval run --task evalsets/<set>/tasks/<task-id> --agent cli \
    --cmd 'claude -p "$(cat {instructions})" --dangerously-skip-permissions' \
    --model claude-sonnet --runs-root evalsets/<set>/runs

# 自研脚本/程序：读 AEL_* 环境变量干活（AEL_INSTRUCTIONS / AEL_WORKSPACE / AEL_INBOX /
# AEL_ATTEMPT_ID / AEL_FEEDBACK），把提交写到 $AEL_INBOX/$AEL_ATTEMPT_ID.json
bin/agent-eval run --task evalsets/<set>/tasks/<task-id> --agent cli \
    --cmd "bash $PWD/evalsets/<set>/agents/my_agent.sh" --runs-root evalsets/<set>/runs
```

路径锚定（实跑验证过的坑）：`--cmd` 的**相对路径以 workspace 为 cwd 解析**，仓库相对路径会失效——

- 临时单跑：直接拼绝对路径，如 `--cmd "bash $PWD/evalsets/<set>/agents/my_agent.sh"`（发起 shell 展开 `$PWD`）；
- 入库的 `agents.yaml`（要跨机器可移植，不能写死绝对路径）：用运行期环境变量锚定，
  `cmd: 'bash "$AEL_RUN_DIR/../../../agents/my_agent.sh"'`——runs 布局固定为
  `<runs-root>/<task-id>/<run-id>`，故 `$AEL_RUN_DIR/../..` 即 runs-root；前提是批跑时
  `--runs-root` 指向集合内的 `runs/`。

多轮修正依赖 `AEL_FEEDBACK`（首轮为空串，之后指向上一轮反馈 JSON）；不读反馈、每轮交同一份答案的 Agent 会以 `max_attempts_reached` 收场。

**http 接入**：`--agent http --endpoint <url>`（框架按轮 POST 任务说明，响应体即提交信封，`204` 放弃）。**不可信 Agent**：加 `--sandbox docker --sandbox-image <img>`（hidden 根本不在容器里）。

冒烟顺序：scripted 回放先证明任务闭环 → 单任务接真实 Agent → 再批跑。这样挂了能立刻分清是任务配错还是 Agent 不行。

## 阶段 3：批跑与度量

```bash
# 单 Agent 过全集 + pass^k 可靠性（k 次全过才算稳定通过）
bin/agent-eval suite --tasks-root evalsets/<set>/tasks --runs-root evalsets/<set>/runs \
    --agent cli --cmd '<同上>' --label my-agent --repeat 3

# 多 Agent 并列对比（scripted 基线 + 真实 Agent 混搭；产出任务×Agent 矩阵与排名）
bin/agent-eval suite --tasks-root evalsets/<set>/tasks --runs-root evalsets/<set>/runs \
    --agents-file evalsets/<set>/agents.yaml

# 跨 run 趋势（通过率/分数怎么走）
bin/agent-eval history --runs-root evalsets/<set>/runs
```

`agents.yaml` 格式见 README「suite」一节。解读要点：

- 多 Agent 横向对比看 suite 的对比面板（`suite_report.md`：pass^k 稳定通过数、平均耗时、自报成本）；
- `history` 按 **(task_id, 适配器名)** 聚合——多个 cli Agent 会混进同一行 `cli`，它适合看单 Agent 的纵向趋势，横向选型请以 suite 对比报告为准；
- 单任务掉分先看 `<run>/report/report.md` 的 failed checks 与 `<run>/feedback/`，再看 `<run>/agent-logs/`（Agent 到底输出了什么）。

## 常见失败排查

| 症状 | 先查 |
| --- | --- |
| `SUBMISSION_INVALID` / 反馈说 schema 不合法 | 信封必填字段是否齐、`attempt_id` 与文件名是否一致、`submission_type` 是否匹配任务分型 |
| Agent 跑完但「本轮无提交」 | 提交是否写到了 `$AEL_INBOX/$AEL_ATTEMPT_ID.json`（而不是 workspace 或别的名字） |
| 退出码 2 | 框架/环境故障（如任务配置在 run 期才暴露的问题），看 stderr 与 `<run>/traces/trace.jsonl` 的 `error` 事件，别急着改 Agent |
| 分数总差一个维度 | `hidden/judge.rules.yaml` 的 `feedback_fail` 提示（`<run>/feedback/`）；确认期望值与 `expected_from` 指针 |
| 想复核判分 | `bin/agent-eval judge --task <taskDir> --submission <run>/inbox/attempt_NNN.json`（离线可复现） |
