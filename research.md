# AgentEval-Lite 项目研究报告

## 1. 概览

这是一个轻量级、本地运行的 AI Agent 评估框架。它把一次评估抽象成“任务定义 + Agent 工作区 + 结构化提交 + 隐藏评审 + 多轮反馈 + 可复现报告”。

项目不是 Web 应用，也不是普通业务系统，而是一个 Java 17 Maven CLI 工具。CLI 入口在 `src/main/java/com/agenteval/cli/Main.java:14`，主要命令包括 `run`、`judge`、`report`、`validate`、`list`、`tool`。

核心用途是评测不同形态的 Agent 是否能在受控材料、受控工具和隐藏评分规则下完成任务。内置任务覆盖代码修复、API payload 生成、文档分析、工具调用纪律和 PRD 评审。

## 2. 项目结构

- `src/main/java/com/agenteval/cli`：命令行入口与子命令。
- `src/main/java/com/agenteval/runner`：一次评估 run 的生命周期编排。
- `src/main/java/com/agenteval/task`：`task.yaml` 加载、校验、说明渲染。
- `src/main/java/com/agenteval/agent`：manual、scripted、cli、http 四类 Agent 适配器。
- `src/main/java/com/agenteval/submission`：提交 JSON 的信封和分型 schema 校验。
- `src/main/java/com/agenteval/judge`：规则评审、脚本评审和分数聚合。
- `src/main/java/com/agenteval/tool`：工具网关与调用留痕（应答库回放为主，声明了 http 后端的工具可 live 外呼并录制）。
- `src/main/java/com/agenteval/report`：从 run 工件重建 JSON/Markdown 报告。
- `tasks/`：内置评估任务，每个任务包含 `task.yaml`、`work/`、`hidden/`、`samples/`。

## 3. 核心流程

主流程从 `agent-eval run` 开始。`RunCommand` 根据 `--agent` 选择 manual、scripted、cli 或 http 适配器，然后调用 `RunManager.execute` 执行评估，见 `src/main/java/com/agenteval/cli/RunCommand.java`。

`RunManager` 是核心编排器。新 run 会加载任务、创建 run 目录、复制 `work/` 到私有 `workspace/`、生成 `instructions.md`、记录 hidden 和 workspace 指纹，见 `src/main/java/com/agenteval/runner/RunManager.java:133` 和 `src/main/java/com/agenteval/workspace/WorkspaceManager.java:47`。

每轮评估中，框架启动 Agent，等待它把 JSON 写入 `inbox/attempt_NNN.json`。自然语言输出不计分；只有进入 inbox 的结构化提交会被校验和评分，见 `src/main/java/com/agenteval/submission/SubmissionManager.java:22`。

提交合法后，`JudgeRunner` 按 `judge.type` 调用规则引擎、脚本评审或两者组合，再按维度权重聚合总分。通过条件是分数达到通过线且没有 blocking 检查失败，见 `src/main/java/com/agenteval/judge/JudgeRunner.java:15`。

若未通过且仍有提交次数，框架把受控反馈写入 `feedback/`，让 Agent 下一轮修正。即使 Agent 自称完成，只要没过线且还有轮次，也会触发轻量 stop-hook 继续评估，见 `src/main/java/com/agenteval/runner/RunManager.java:298`。

## 4. 关键设计

Work/Judge 隔离是项目的核心边界。Agent 只应看到复制后的 `workspace/`、`instructions.md` 和反馈目录；答案、规则和 mock 工具应答放在 `hidden/`。说明渲染器明确不暴露 hidden 路径或规则细节，见 `src/main/java/com/agenteval/task/InstructionsRenderer.java:5`。

规则评审强调确定性。`RulesJudge` 支持 JSON Schema、JSONPath、列表覆盖率、文件检查、真实修改核验、命令执行、工具调用核验和 canary 泄露检查。命令型检查会在 workspace 临时副本上执行，避免污染 Agent 工作区，见 `src/main/java/com/agenteval/judge/RulesJudge.java:30`。

工具调用走框架网关。`ToolGateway` 只允许 task allowlist 中的工具，默认（replay 模式）从 `hidden/tools/<name>.responses.yaml` 匹配 mock 响应，同时把调用写入 trace。评审可核对提交里的 `call_id` 是否真实存在，见 `src/main/java/com/agenteval/tool/ToolGateway.java`。声明了 `backend`（http）的工具在 `AEL_TOOL_MODE=live` 下真实外呼 task.yaml 静态声明的 URL（凭证经 `${ENV:NAME}` 从框架进程环境解析），并把交换以应答库同格式存档到 `<run>/tools/<name>.recorded.yaml`，复制回 `hidden/tools/` 即完成录制到回放的晋升，见 `src/main/java/com/agenteval/tool/ToolHttpBackend.java`。

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

接入面同日完成 Phase 2：新增 `HttpAgentAdapter`（窄口径：框架按轮 POST 任务说明与反馈，`200` 响应体即提交、`204` 表示放弃，服务不可达按基础设施故障上抛），`run`/`suite`/`--agents-file` 全线支持 `http` 类型；任务工程化补上 `agent-eval task init` 脚手架（产物开箱即过 validate 与 fail→pass 回放闭环）与 `validate` 规则深度 lint（`expected_from` 断链、`schema_file` 缺失、check 引用白名单外工具在静态阶段拦截）。

安全硬化也继续推进。`RulesJudge` 新增 `world_state` 终态比对能力，复用签名可信的成功 `tool_call` 事件重放世界终态；工具调用核验仍以 HMAC trace 为信任边界。`no_canary_leak` 已覆盖 `workspace/`、`agent-logs/`、`inbox/` 和 `traces/`，能抓住通过工具入参夹带 canary 的外泄路径。

Phase 3（真实信号）同日落地，三件事共享同一设计立场——真实/非确定性信号可以引入，但必须被围栏住，不得侵蚀确定性判分核心：

- **真实工具 http 后端**：`task.yaml` 的 `allowed_tools[].backend` 静态声明 URL/method/headers（白名单即声明本身，Agent 入参改不了外呼目标），`AEL_TOOL_MODE=live` 真实外呼并录制 `<run>/tools/<name>.recorded.yaml`，默认 replay 一律走应答库、后端工具无应答库则 fail-closed。db 只读后端因需引入 JDBC 依赖刻意不做。见 `ToolMode`、`ToolHttpBackend`、`ToolGateway`。
- **auto-eval 周期采样**：`runtime.auto_eval_interval_seconds > 0` 时 `AutoEvalSampler` 后台线程按间隔快照 workspace 并跑隐藏评审，产出 `auto_eval_sampled` trace 事件与报告 `auto_eval` 得分轨迹段；结果不回注 Agent、不影响轮次判定与最终成绩，采样产物隔离在 `judge/auto/`。见 `src/main/java/com/agenteval/runner/AutoEvalSampler.java`。
- **LLM judge（`llm_rubric`）**：第 15 种 check，按 hidden rubric 让判分模型给主观维度打 0~1 分（`earned = points × 模型分`）。判分模型经 `AEL_LLM_ENDPOINT/MODEL/API_KEY` 接 OpenAI 兼容端点；validate 深度 lint 强制 llm 检查有效权重 ≤ 满分 30% 且禁止 blocking；temperature 0 + 强 JSON 契约 + 请求/响应全量存档 `judge/`；未配置或连续失败按评审设施故障 fail-closed，绝不静默打分；结果如实标注 `reproducibility.deterministic=false`。内置 5 任务与 CI 回放集保持零 llm 检查。见 `src/main/java/com/agenteval/judge/LlmRubricJudge.java`。

已验证命令（2026-07-07 Phase 3 后复核）：

- `mvn -q -B clean verify`：通过，150 个测试，JaCoCo 指令覆盖率 0.7866（门禁下限 0.75），Checkstyle 无违规。
- `bin/agent-eval suite --tasks-root tasks --fail-on-not-passed`：通过，5/5 任务稳定通过。
- `bash redteam/run_all.sh`：通过，14 项中 13 DEFENDED / 1 登记基线 VULNERABLE，INFRA=0 / CHECK=0。
- `bash redteam/test_gate.sh`：通过，门禁判定 fail-closed 契约 7 用例自测全绿。
- `bash bin/ci-smoke.sh`：通过，四道门禁闭环。

仍需关注：本地同用户文件系统下，Agent 外科式读取 `hidden/` 后只抄答案值仍不可检测，需 Docker Runner 或等价挂载隔离根治。红队脚本目前允许 1 个登记基线漏洞；新增漏洞只要超过 `RT_ALLOWED_VULN` 才会让门禁失败。
