# PLAYBOOK：高频改造手册

面向「要动手改代码/加任务」的人或 AI：每条 recipe 给出按序要碰的文件、必须保持的不变量、最便宜的验证闸与完成判据。定位类靠 `docs/CODEMAP.md`；跨包不变量全文见 `src/main/java/com/agenteval/AGENTS.md`；通用验证阶梯见根 `AGENTS.md`。

统一收尾动作（所有 recipe 共享）：改了类/Javadoc/CLI/枚举/任务后执行 `bash bin/gen-codemap.sh` 并连同 `docs/CODEMAP.md` 一起提交；CI 有漂移门禁。

---

## 1. 新增 judge check 类型

**场景**：规则引擎要支持一种新的确定性断言（如新的 workspace/trace 检查）。

1. `src/main/java/com/agenteval/judge/RulesFile.java`：`SUPPORTED_TYPES` 注册类型名（snake_case）。
2. `src/main/java/com/agenteval/judge/RulesJudge.java`：`check()` 的 switch 加分支 + 私有实现方法。产出统一用 `CheckOutcome`；对外失败文案走 `feedback_fail`（禁含期望值）。
3. 若该类型有「run 时才炸」的静态可查错误（引用 hidden 文件、引用工具名等）：`src/main/java/com/agenteval/cli/ValidateCommand.java#lintRules` 加深度 lint。
4. `README.md`：「check 类型」清单补一句语义说明（README 是该清单的语义权威，CODEMAP 只列名）。
5. 测试：`RulesJudgeTest`（正/负例）；涉及签名/nonce/canary 的加 `RulesJudgeHardeningTest`；加了 lint 则补 `ValidateLintTest`。

**不变量**：确定性（同输入必同分，禁止读时钟/网络/随机数——非确定性只允许 `llm_rubric`）；只读 `JudgeInput` 视图；需要执行副作用的一律在临时副本上做（参考 `command` 的 ephemeral workspace）。
**验证**：`mvn -q test -Dtest='RulesJudge*,ValidateLintTest'` → `mvn -q test`。
**完成判据**：新类型有正/负例测试；validate 能拦住其静态配置错误；README 清单与 CODEMAP 已更新。

## 2. 新增 CLI 子命令

1. `src/main/java/com/agenteval/cli/XxxCommand.java`：picocli `@Command` + 实现 `Callable<Integer>`，类级 Javadoc 首句写清「`agent-eval xxx`：干什么」。
2. `src/main/java/com/agenteval/cli/Main.java`：`subcommands` 注册。
3. `README.md`：「其余命令」加一行。

**不变量**：退出码契约 0/1/2/3（见代码层指南）；cli 包只做参数解析与装配，业务逻辑放对应域包；纯读命令（report/export/history 一类）保持幂等。
**验证**：`mvn -q package` 后手跑 `bin/agent-eval xxx --help` 与一条真实路径；对应测试放 `cli/` 或 `integration/`。
**完成判据**：`--help` 文案完整；有至少一条端到端断言；CODEMAP 的「CLI 命令面」出现新命令（生成器自动抽取 `@Command`）。

## 3. 新增 Agent 适配器

1. `src/main/java/com/agenteval/agent/YyyAgentAdapter.java`：实现 `AgentAdapter`（`name()` + `runAttempt()`）；子进程形态复用 `AgentProcess`（超时强杀/日志合流落盘已封装）。
2. `src/main/java/com/agenteval/cli/RunCommand.java#buildAdapter`：switch 加分支与所需 `--xxx` 选项。
3. `src/main/java/com/agenteval/runner/SuiteRunner.java`：`AgentSpec` 加静态工厂（label + factory）。
4. `src/main/java/com/agenteval/cli/SuiteCommand.java`：`--agent` 取值说明 + `parseAgentsFile` 的 type 分支（让 agents.yaml 多 Agent 对比可用）。
5. `README.md`：「Agent 接入方式」补编号与示例。

**不变量**：适配器必须自行遵守 `AttemptInput.timeout()`；Agent 只能触碰 workspace/inbox/feedback/instructions 四个点；Agent 不可达/环境缺失按评估基础设施故障上抛（退出码 2 路径），绝不误记为 Agent 低分。
**验证**：`integration/` 加端到端（本地可控对端，参考 `HttpAgentRunTest` 起真实本地服务的做法）→ `mvn -q test`。
**完成判据**：`run` 与 `suite --agents-file` 两条入口都能驱动新适配器；失败路径语义有测试钉死。

## 4. 新增评估任务

先读 `tasks/AGENTS.md`（目录契约 + hidden 防泄露检查单，那里是权威）。骨架流程：

1. `bin/agent-eval task init --id my-task-001` 生成开箱即跑的脚手架；
2. 改 `task.yaml`（task_id=目录名、维度权重和=max_score、tier 归类）与 `work/` 材料；
3. 改 `hidden/judge.rules.yaml`；期望值放 `hidden/expected/` 用 `expected_from` 引用；
4. 更新 `samples/` 三件套，replay.yaml 编排「第 1 轮失败 → 按反馈修正 → 第 2 轮通过」；
5. 逐条过 hidden 防泄露检查单。

**验证**：`bin/agent-eval validate --task tasks/my-task-001` → scripted 回放跑通 → `bin/agent-eval suite --tasks-root tasks --fail-on-not-passed` → `mvn -q test`（`TasksValidationTest` 门禁）。
**完成判据**：validate 零告警、回放 fail→pass 闭环通过、套件门禁不破、CODEMAP 任务库表已更新。

## 5. 新增 trace 事件类型

1. `src/main/java/com/agenteval/trace/TraceEventType.java`：加枚举值 + 单行 Javadoc（语义会被 CODEMAP 自动抽进事件表）。
2. 在产生该事实的组件调用 `TraceLogger#log(type, attemptId, payload)` 落事件（主发射点集中在 `RunManager`，工具类事件在 `ToolGateway`）。
3. 需要专属 span/属性语义时：`src/main/java/com/agenteval/trace/OtlpTraceExporter.java#build` 的 switch 加分支；不加则自动走 default 的通用 event 附着（多数事件够用）。
4. 若报告要呈现它：`report/ReportGenerator` 对应聚合段。

**不变量**：trace 是 append-only 的行为留痕，事件一律经 `TraceLogger`（自动带 HMAC 签名与序号），绝不手写 JSONL；payload 里不得携带 hidden 内容（canary 扫描覆盖 traces/）。
**验证**：`mvn -q test -Dtest='TraceLoggerTest,OtlpTraceExporterTest'` → `mvn -q test`。
**完成判据**：事件有发射点与测试断言；`agent-eval export` 对含新事件的 run 仍产出合法 OTLP；CODEMAP 事件表已更新。

## 6. 新增红队攻击用例

先读 `redteam/AGENTS.md`（四态语义 + 基线登记规则，那里是权威）。骨架流程：

1. 载荷放对应攻击面目录（`A-hidden/`…`J-llmjudge/`，新攻击面则新建 `X-name/`）；
2. `redteam/run_all.sh` 加编排段：跑攻击 → 用 `jread` 安全读产物（防 fail-open）→ `record "<名称>" "<期望>" "<实测>" "<判定>"`；
3. 结论改变基线时同步三处：README「安全边界」、`.github/workflows/ci.yml` 注释、`RT_ALLOWED_VULN` 语义；
4. 若动了门禁判定逻辑本身（`gate_lib.sh`），先给 `test_gate.sh` 加正/负向自测。

**验证**：`bash redteam/test_gate.sh && bash redteam/run_all.sh`，核对矩阵与 `runs/redteam/redteam_report.json`。
**完成判据**：新用例在矩阵中有确定判定；门禁在预期基线下通过；基线变更三处同步且 PR 说明理由。
