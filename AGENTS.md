# 仓库指南（AI 协作入口）

AgentEval-Lite：零外部依赖、单 jar、目录即隔离的本地 AI Agent 评估框架（Java 17 + Maven + picocli）。
核心机制：Work/Judge 隔离、结构化提交契约、隐藏判分、多轮受控反馈、全程留痕（HMAC 签名）、可复现评分。

## 60 秒理解

一次评估 run 的数据流主线：

```text
task.yaml ──TaskSpecLoader──▶ RunManager attempt 循环
    [AgentAdapter 驱动 Agent → inbox 收件（SubmissionManager 双重 schema 校验）
     → JudgeRunner 隐藏评审（RulesJudge 确定性 check / ScriptJudge / LlmRubricJudge）
     → FeedbackPolicy 受控反馈 → RunStateStore 快照]
──▶ BestAttemptSelector ──▶ ReportGenerator（report.json + report.md）
全程 TraceLogger 留痕（TraceSigner HMAC 签名）；ToolGatewayServer 常驻框架进程供 Agent 调工具。
```

包依赖方向单向分层，新代码不得反向引用：`cli → runner → {agent, judge, submission, tool, trace, report, state, workspace} → task → util`

| 包 | 一句话职责 |
| --- | --- |
| `cli` | picocli 命令面（run/suite/judge/report/export/validate/list/tool/task/history），只做参数解析与装配 |
| `runner` | RunManager（单 run 生命周期编排）、SuiteRunner（批跑/多 Agent 对比）、AutoEvalSampler（后台采样） |
| `agent` | AgentAdapter SPI 与 manual/scripted/cli/http/docker 适配器、AgentProcess 子进程内核 |
| `task` | TaskSpec 强类型规格 + fail-fast 加载校验 + instructions 渲染 + 枚举族（TaskType/TaskTier 等） |
| `workspace` | work→workspace 复制、文件级 SHA-256 基线、hidden 完整性指纹 |
| `submission` | 唯一提交通道：信封+分型双重 JSON Schema 校验、attempt 登记 |
| `judge` | JudgeRunner 编排 + RulesJudge（全部确定性 check）+ ScriptJudge + LlmRubricJudge + FeedbackPolicy |
| `tool` | ToolGateway/常驻 Server/瘦 Client、mock 应答库与 http 真实后端、allowlist fail-closed |
| `trace` | append-only JSONL 留痕 + HMAC 签名（TraceSigner/TraceSecret）+ OTLP/OpenInference 导出 |
| `report` | 单 run 报告生成 + BestAttemptSelector 最佳轮次选择 |
| `state` | RunMeta/RunState/RunStateStore（resume 原子快照） |
| `util` | Jsons/Hashes/Ids/Dirs/JsonPaths 零依赖工具，不得依赖其他包 |

JSON Schema 在 `src/main/resources/schemas`；测试在 `src/test/java`（端到端集中在 `integration` 包）；`target/` 与 `runs/` 是生成目录，不入库。

## 何时读什么（分区路由）

- **理解/修改 Java 代码** → 先查 `docs/CODEMAP.md`（自动生成的类级地图：每个类一句话 + CLI 面 + check/事件清单），再读 `src/main/java/com/agenteval/AGENTS.md`（扩展点接线表 + 跨包不变量）。
- **新增/修改评估任务（tasks/）** → 先读 `tasks/AGENTS.md`（目录契约 + hidden 防泄露检查单）。
- **红队攻防（redteam/）** → 先读 `redteam/AGENTS.md`（四态语义 + 基线登记规则）。
- **做一类典型改造**（新 check 类型 / CLI 子命令 / Agent 适配器 / 任务 / trace 事件 / 红队用例）→ 按 `docs/PLAYBOOK.md` 对应 recipe 执行。
- **理解设计动机** → `docs/03-AgentEval-Lite-设计方案.md`（架构与安全模型）、`docs/04-成熟评估框架调研与ROI报告.md`（取舍依据）、`README.md`（用户视角全景）。

仓库级 skills 在 `.agents/skills/`（Codex 原生发现；`.claude/skills` 为符号链接，Claude Code 同源共用）：`ael-verify`（按改动范围选验证闸）、`ael-new-task`（内置任务库编写工作流）、`ael-build-evalset`（AI 辅助用户从零建私有测评集并接入真实 Agent；可运行示例见 `evalsets/demo-ops-agent/`）、`ael-analyze-results`（测评完成后解读 run/suite/history 产物、定性问题归属并给优化建议）。**用户私有测评集放 `evalsets/<set>/`，不要混进内置 `tasks/`**（后者是框架自测库，会进 CI 门禁）。

## 构建与验证阶梯（从便宜到贵，按改动范围选最便宜的闸）

1. 单测试类：`mvn -q test -Dtest=RulesJudgeTest`
2. 全量测试：`mvn -q test`（JUnit 5 + AssertJ；Docker 端到端用例在无 Docker 环境自动跳过）
3. 打包：`mvn -q package` → `target/agent-eval-lite-*-cli.jar`（`bin/agent-eval` 自动定位）
4. 任务体检：`bin/agent-eval validate --task tasks/<task-id>`（含深度 lint）
5. 回放冒烟：`bin/agent-eval suite --tasks-root tasks --fail-on-not-passed`
6. 门禁不重跑测试：`SKIP_TESTS=1 bash bin/ci-smoke.sh`（需已构建 jar）
7. 提交前完整门禁：`bash bin/ci-smoke.sh`（与 `.github/workflows/ci.yml` 同口径：codemap 漂移 → verify → validate → suite → 红队）

**代码地图维护**：`docs/CODEMAP.md` 由 `bin/gen-codemap.sh` 从 Javadoc 首句自动生成，禁止手改。凡增删类 / 改类 Javadoc 首句 / 增删 CLI 子命令、check 类型、trace 事件、任务，都要重跑 `bash bin/gen-codemap.sh` 并提交结果——CI 有漂移门禁，忘了会失败。

## 编码风格与命名约定

使用 Java 17、UTF-8 和四空格缩进。包名保持小写；类名和 record 使用 `PascalCase`；方法、字段和局部变量使用 `camelCase`。偏向使用不可变 record 或 `final` 类表达值对象。公开 API 按现有风格补充 Javadoc（中文），核心类型保留 `@author` 与 `@since`。**类级 Javadoc 首句是 CODEMAP 的数据源**：新增类必须写，且首句要独立成句地概括职责。

任务 ID 和任务目录使用 kebab-case 加数字后缀，例如 `code-fix-001`。YAML 字段使用 snake_case，与现有 `task.yaml` 保持一致。

## 测试指南

单元测试命名为 `*Test.java`，放在 `src/test/java` 下与被测包对应的位置。集成测试放入 `integration` 包。新增任务样例时，应提供 `samples/attempt-pass.json`、`samples/attempt-fail.json` 和 `samples/replay.yaml`，并运行 `mvn -q test` 与 `bin/agent-eval validate --task tasks/<task-id>`。

## 提交与 Pull Request 规范

使用简短祈使句作为提交标题，例如 `Add task validation checks`。Pull Request 应说明变更内容、列出已运行的验证命令，并明确指出任务样例或 Schema 的改动。不要在 PR 描述中泄露 `hidden/expected` 答案或私有评审细节。

## 安全红线（违反即破坏框架公信力）

- **hidden/ 是评审禁区**：expected 答案、judge 规则、mock 应答库只存在于 `tasks/<task-id>/hidden/`，绝不复制进 `work/`、`samples/`、`instructions`、`feedback_fail` 对外文案、公开文档、报告或 PR 描述。
- **trace 可信链**：签名密钥在 Agent 运行期间仅存内存（`TraceSecret` 的安全不变量）；判分只认 HMAC 可核验事件。改动 `trace`/`tool`/`judge` 的 command-nonce 相关代码前，先读 `src/main/java/com/agenteval/AGENTS.md` 的不变量清单，改后必须跑红队门禁。
- **生成物纪律**：`runs/`、`target/` 不入库；`docs/CODEMAP.md` 只能经 `bin/gen-codemap.sh` 更新。
