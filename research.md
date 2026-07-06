# AgentEval-Lite 项目研究报告

## 1. 概览

这是一个轻量级、本地运行的 AI Agent 评估框架。它把一次评估抽象成“任务定义 + Agent 工作区 + 结构化提交 + 隐藏评审 + 多轮反馈 + 可复现报告”。

项目不是 Web 应用，也不是普通业务系统，而是一个 Java 17 Maven CLI 工具。CLI 入口在 `src/main/java/com/agenteval/cli/Main.java:14`，主要命令包括 `run`、`judge`、`report`、`validate`、`list`、`tool`。

核心用途是评测不同形态的 Agent 是否能在受控材料、受控工具和隐藏评分规则下完成任务。内置任务覆盖代码修复、API payload 生成、文档分析、工具调用纪律和 PRD 评审。

## 2. 项目结构

- `src/main/java/com/agenteval/cli`：命令行入口与子命令。
- `src/main/java/com/agenteval/runner`：一次评估 run 的生命周期编排。
- `src/main/java/com/agenteval/task`：`task.yaml` 加载、校验、说明渲染。
- `src/main/java/com/agenteval/agent`：manual、scripted、cli 三类 Agent 适配器。
- `src/main/java/com/agenteval/submission`：提交 JSON 的信封和分型 schema 校验。
- `src/main/java/com/agenteval/judge`：规则评审、脚本评审和分数聚合。
- `src/main/java/com/agenteval/tool`：mock 工具网关与工具调用留痕。
- `src/main/java/com/agenteval/report`：从 run 工件重建 JSON/Markdown 报告。
- `tasks/`：内置评估任务，每个任务包含 `task.yaml`、`work/`、`hidden/`、`samples/`。

## 3. 核心流程

主流程从 `agent-eval run` 开始。`RunCommand` 根据 `--agent` 选择 manual、scripted 或 cli 适配器，然后调用 `RunManager.execute` 执行评估，见 `src/main/java/com/agenteval/cli/RunCommand.java:67`。

`RunManager` 是核心编排器。新 run 会加载任务、创建 run 目录、复制 `work/` 到私有 `workspace/`、生成 `instructions.md`、记录 hidden 和 workspace 指纹，见 `src/main/java/com/agenteval/runner/RunManager.java:133` 和 `src/main/java/com/agenteval/workspace/WorkspaceManager.java:47`。

每轮评估中，框架启动 Agent，等待它把 JSON 写入 `inbox/attempt_NNN.json`。自然语言输出不计分；只有进入 inbox 的结构化提交会被校验和评分，见 `src/main/java/com/agenteval/submission/SubmissionManager.java:22`。

提交合法后，`JudgeRunner` 按 `judge.type` 调用规则引擎、脚本评审或两者组合，再按维度权重聚合总分。通过条件是分数达到通过线且没有 blocking 检查失败，见 `src/main/java/com/agenteval/judge/JudgeRunner.java:15`。

若未通过且仍有提交次数，框架把受控反馈写入 `feedback/`，让 Agent 下一轮修正。即使 Agent 自称完成，只要没过线且还有轮次，也会触发轻量 stop-hook 继续评估，见 `src/main/java/com/agenteval/runner/RunManager.java:298`。

## 4. 关键设计

Work/Judge 隔离是项目的核心边界。Agent 只应看到复制后的 `workspace/`、`instructions.md` 和反馈目录；答案、规则和 mock 工具应答放在 `hidden/`。说明渲染器明确不暴露 hidden 路径或规则细节，见 `src/main/java/com/agenteval/task/InstructionsRenderer.java:5`。

规则评审强调确定性。`RulesJudge` 支持 JSON Schema、JSONPath、列表覆盖率、文件检查、真实修改核验、命令执行、工具调用核验和 canary 泄露检查。命令型检查会在 workspace 临时副本上执行，避免污染 Agent 工作区，见 `src/main/java/com/agenteval/judge/RulesJudge.java:30`。

工具调用走框架网关。`ToolGateway` 只允许 task allowlist 中的工具，并从 `hidden/tools/<name>.responses.yaml` 匹配 mock 响应，同时把调用写入 trace。评审可核对提交里的 `call_id` 是否真实存在，见 `src/main/java/com/agenteval/tool/ToolGateway.java:21`。

报告不是事实源，而是 run 工件的视图。`ReportGenerator` 从 `meta.json`、`run_state.json`、`judge/`、`trace.jsonl` 和 `inbox/` 重建 `report.json` 与 `report.md`，见 `src/main/java/com/agenteval/report/ReportGenerator.java:29`。

## 5. 项目定位

一句话：这是一个面向 AI Agent 的本地基准测试与评测执行器。

它适合用来：

- 为某类 Agent 任务编写可复现的评测用例。
- 比较不同 Agent、模型或提示词在同一任务上的表现。
- 验证 Agent 是否遵守工具调用、结构化提交、证据引用和不越界读取规则。
- 在 CI 或本地回归中用 scripted replay 检查任务和评审规则是否稳定。

它当前更像 Phase 1 的单机评估框架：强调目录隔离、确定性规则、结构化输出和报告复现。它还不是强隔离沙箱；README 和代码都表明当前安全边界主要依赖约定、指纹、trace 和 canary 检查。

## 6. 修改影响面提示

新增任务通常只需要改 `tasks/<task-id>/` 并运行 `validate` 与 scripted replay。新增提交类型会影响 schema、`TaskType`、`InstructionsRenderer`、示例任务和测试。新增评审 check 会影响 `RulesJudge`、规则文件模型、反馈文本和相关单元测试。

## 7. 2026-07-07 更新复查

本轮更新后，项目从单任务 runner 扩展为可批量评估的本地 Agent benchmark。`Main` 已注册 `suite` 子命令；`SuiteRunner` 支持全任务回放、真实 CLI Agent 批跑、`repeat=k` 的 pass^k 可靠性，以及多 Agent 对比报告。CI 侧新增 `.github/workflows/ci.yml` 与 `bin/ci-smoke.sh`，本地门禁为 Maven 测试与打包、任务规格体检、suite 冒烟、红队回归四步。

安全硬化也继续推进。`RulesJudge` 新增 `world_state` 终态比对能力，复用签名可信的成功 `tool_call` 事件重放世界终态；工具调用核验仍以 HMAC trace 为信任边界。`no_canary_leak` 已覆盖 `workspace/`、`agent-logs/`、`inbox/` 和 `traces/`，能抓住通过工具入参夹带 canary 的外泄路径。

已验证命令：

- `mvn -q clean test`：通过，93 个测试。
- `bin/agent-eval suite --tasks-root tasks --fail-on-not-passed`：通过，5/5 任务稳定通过。
- `bash redteam/run_all.sh`：通过，12 DEFENDED / 1 登记基线 VULNERABLE。
- `bash bin/ci-smoke.sh`：通过，四道门禁闭环。

仍需关注：本地同用户文件系统下，Agent 外科式读取 `hidden/` 后只抄答案值仍不可检测，需 Docker Runner 或等价挂载隔离根治。红队脚本目前允许 1 个登记基线漏洞；新增漏洞只要超过 `RT_ALLOWED_VULN` 才会让门禁失败。
