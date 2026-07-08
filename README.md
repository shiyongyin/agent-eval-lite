# AgentEval-Lite

> 企业内部 AI Agent 测试脚手架：把真实业务任务变成可提交、可判分、可回放、可进 CI 的评测集。

[![CI](https://github.com/shiyongyin/agent-eval-lite/actions/workflows/ci.yml/badge.svg)](https://github.com/shiyongyin/agent-eval-lite/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

AgentEval-Lite 是一个面向企业内部 AI Agent 的本地优先测试脚手架。它回答的问题不是“这个 Agent 聊得像不像人”，而是“它有没有把任务做对，证据在哪，换个时间重评分数是否一致”。

它把一次评估做成可版本化、可回放、可审计的目录契约：任务材料放在 `work/`，隐藏答案和 judge 规则放在 `hidden/`，Agent 只能写结构化提交，框架负责隐藏判分、受控反馈、签名留痕和报告生成。

## 交互式导览

想先看图和流程，可以直接打开单文件导览：

- GitHub 仓库：[shiyongyin/agent-eval-lite](https://github.com/shiyongyin/agent-eval-lite)
- 仓库文件：[docs/05-交互式导览.html](docs/05-交互式导览.html)
- 在线网页：[shiyongyin.github.io/agent-eval-lite](https://shiyongyin.github.io/agent-eval-lite/)

GitHub 仓库里的 `.html` 文件点开会显示源码；上面的 GitHub Pages 链接才是网页渲染版。这个 HTML 不依赖外部服务，下载仓库后也可以在浏览器里直接打开。

## 适合什么

- 企业内部评估命令行 Agent、HTTP Agent、自研 Agent、人工基线。
- 把代码修复、API payload 生成、文档分析、工具调用纪律、PRD 评审等业务场景做成结构化验收任务。
- 把 Agent 能力评估纳入 CI、红队回归、版本对比和多 Agent 横评。
- 为私有业务场景搭建自己的 evalset，同时保留隐藏答案和评分规则。

## 用户画像

| 用户 | 常见问题 | AgentEval-Lite 提供什么 |
| --- | --- | --- |
| AI Agent 平台 / 中台团队 | 多个 Agent、模型、prompt、工具链怎么选型和回归 | 统一任务集、`agents.yaml` 横评、`repeat` 稳定性、CI 门禁 |
| 业务自动化团队 | 业务 Agent 上线前怎么证明真的会干活 | 把真实业务流程改写成带隐藏标准答案的任务，并输出可复核报告 |
| QA / 工程效能团队 | Agent 能力回归无法自动化，报告不可复现 | suite 批跑、确定性回放、历史趋势和离线复算 |
| 安全 / 治理团队 | 被评 Agent 可能偷看答案、伪造工具调用、刷分 | Work/Judge 隔离、HMAC trace、canary、防逃逸红队门禁 |
| Agent 开发者 | 改完 Agent 不知道弱点在哪 | 多轮受控反馈、维度化分数、trace 和失败证据 |

## 功能边界

它不是榜单型 benchmark，也不是 Prompt 单轮评分器。更准确地说，它是企业内部把私有业务场景沉淀成 Agent 测试资产的脚手架。

| 同类方向 | 更擅长 | AgentEval-Lite 更擅长 |
| --- | --- | --- |
| promptfoo / DeepEval | prompt、LLM 输出、断言和模型辅助评分 | 多轮 Agent 任务、隐藏判分、结构化提交、工具调用可信核验 |
| Ragas | RAG 问答质量、faithfulness、检索引用指标 | 非 RAG 专项的通用业务任务验收，例如代码、API、文档、工具链 |
| Inspect AI | 更完整的生产级 eval 框架、模型和沙箱生态 | 单 jar、本地目录契约、企业内部私有 evalset 的低门槛落地 |
| SWE-bench / tau-bench | 公共基准任务和研究评测 | 把公司自己的流程、接口、文档、工具权限变成私有测试集 |
| Phoenix / Langfuse | trace 看板、观测和分析平台 | 评测执行、隐藏评分、签名留痕；也可导出 OTLP/OpenInference 给看板消费 |

## 核心机制

| 机制 | 作用 |
| --- | --- |
| Work / Judge 隔离 | Agent 只看到 `workspace/` 和 `instructions.md`；`hidden/` 只给 judge 使用 |
| 结构化提交 | 唯一有效提交是写入 `inbox/attempt_NNN.json` 的 JSON 信封 |
| 隐藏判分 | 规则引擎、脚本 judge、低权重 `llm_rubric` 都在评审侧运行 |
| 多轮反馈 | 每轮只回传受控反馈，`expected`、私有诊断和隐藏规则不泄露 |
| 签名 trace | 工具调用等关键事件用 HMAC 签名，判分只认可信事件 |
| 可复现报告 | 报告记录 engine 版本、提交指纹、workspace 指纹和 hidden 指纹 |
| Docker 沙箱 | 对不可信 Agent 可用 `--sandbox docker`，容器只挂载 Agent 可触碰区 |

一次 run 的主链路：

```text
task.yaml
  -> RunManager
  -> AgentAdapter 执行 Agent
  -> SubmissionManager 校验提交
  -> JudgeRunner 隐藏评审
  -> FeedbackPolicy 受控反馈
  -> ReportGenerator 生成 report.json / report.md
```

## 快速开始

要求：

- Java 17+
- Maven 3.9+
- Docker 可选，仅 Docker 沙箱和强制 Docker 红队门禁需要

```bash
# 构建 CLI fat jar
mvn -q package

# 查看内置任务
bin/agent-eval list

# 跑一个确定性回放示例
bin/agent-eval run --task tasks/api-payload-001 --agent scripted \
    --script tasks/api-payload-001/samples/replay.yaml

# 查看报告
cat runs/api-payload-001/run_*/report/report.md
```

初始化团队自己的私有测评集：

```bash
bin/agent-eval evalset init --id my-agent
bin/agent-eval task init --id first-task-001 --tasks-root evalsets/my-agent/tasks
```

一键本地门禁：

```bash
bash bin/ci-smoke.sh
```

它与 GitHub Actions 同口径，包含 CODEMAP 漂移检查、测试、任务集回放和红队回归。

## Agent 接入

### manual

把人当作被评 Agent，适合建立人工基线。

```bash
bin/agent-eval run --task tasks/prd-review-001 --agent manual --submission my.json
```

### scripted

确定性回放，适合 CI、任务自测和框架回归。

```bash
bin/agent-eval run --task tasks/code-fix-001 --agent scripted \
    --script tasks/code-fix-001/samples/replay.yaml
```

### cli

驱动任意命令行 Agent。命令模板支持 `{instructions}`、`{workspace}`、`{inbox}`、`{attempt_id}`、`{feedback}`、`{run_dir}`。

```bash
bin/agent-eval run --task tasks/code-fix-001 --agent cli \
    --cmd 'claude -p "$(cat {instructions})" --dangerously-skip-permissions' \
    --model claude-sonnet
```

对不可信或强对抗 Agent，使用 Docker 沙箱：

```bash
bin/agent-eval run --task tasks/code-fix-001 --agent cli --sandbox docker \
    --sandbox-image my-agent-image:latest \
    --cmd 'my-agent --instructions {instructions}'
```

Docker 模式只挂载 `workspace/`、`inbox/`、`feedback/` 和 `instructions.md`。`hidden/`、`judge/`、`traces/`、任务目录和宿主家目录不会进入容器。

### http

评估服务形态的 Agent。框架每轮 POST 任务说明和反馈，响应体就是本轮提交信封。

```bash
bin/agent-eval run --task tasks/api-payload-001 --agent http \
    --endpoint http://localhost:8080/agent \
    --http-header 'Authorization: Bearer xxx'
```

## 批跑和对比

```bash
# 批跑内置任务库
bin/agent-eval suite --tasks-root tasks --fail-on-not-passed

# 真实 Agent 连跑全部任务，repeat=3 表示 pass^3 可靠性
bin/agent-eval suite --agent cli --cmd '...' --label my-agent --repeat 3

# 多 Agent 并列对比
bin/agent-eval suite --agents-file agents.yaml --repeat 2

# 只跑某个任务分层
bin/agent-eval suite --tier smoke --fail-on-not-passed

# 汇总历史 run 趋势
bin/agent-eval history --runs-root runs
```

`suite` 会生成 `suite_report.json` 和 `suite_report.md`，包含小团队操作摘要、任务矩阵、稳定通过数、失败规则热点、平均耗时和可选 usage 成本聚合。

`evalsets/demo-ops-agent/` 提供了一个完整私有测评集示例，包含两个任务、三个 Agent 和对比面板命令。

## 小团队落地

没有具体业务场景时，先补通用脚手架，不要硬造假业务题库：

```bash
bin/agent-eval evalset init --id my-agent
```

生成的 `evalsets/my-agent/` 包含：

- `agents.yaml`：scripted 基线、current、candidate 三方横评清单。
- `scripts/run-agent.sh`：团队 Agent 接入包装器。
- `tasks/`：私有任务库入口。
- `README.md`：本集合的标准命令。

推荐路线：

1. 先做 5-10 个 `smoke` 任务，保证每次改 Agent 能快速挡退化。
2. 再扩到 20-50 个 `regression` 任务，合并前或发版前跑。
3. 每个任务进入门禁前，用 `ael-review-task-quality` 或 [docs/07-任务质量清单.md](docs/07-任务质量清单.md) 审一遍。
4. 用 `suite_report.md` 的“小团队操作摘要”先看未稳定通过任务、故障任务、flaky 任务和失败规则热点。

详细路径见 [docs/06-小团队落地指南.md](docs/06-小团队落地指南.md)。可复制模板见 [evalsets/_template](evalsets/_template)。

有 AI 编码助手时，建议直接让 AI 使用项目级 skills：

- “用 `ael-build-evalset` 基于 `evalset init` / `evalsets/_template` 帮我建私有测评集。”
- “用 `ael-new-task` 在这个 evalset 里生成一个任务脚手架。”
- “用 `ael-review-task-quality` 审查这个任务能不能进 smoke/regression。”
- “用 `ael-verify` 跑提交前最小验证。”

## 写一个任务

从脚手架开始：

```bash
bin/agent-eval task init --id my-task-001
```

任务目录契约：

```text
tasks/<task-id>/
├── task.yaml
├── work/
├── hidden/
│   ├── judge.rules.yaml
│   ├── expected/
│   └── tools/
└── samples/
    ├── attempt-pass.json
    ├── attempt-fail.json
    └── replay.yaml
```

任务自测：

```bash
bin/agent-eval validate --task tasks/my-task-001
bin/agent-eval run --task tasks/my-task-001 --agent scripted \
    --script tasks/my-task-001/samples/replay.yaml
```

内置规则 check 包括 JSON Schema、JSONPath 断言、列表覆盖率、来源引用校验、workspace 文件检查、changed files 核验、command、工具调用轨迹、world state 终态比对、canary 泄露检查和 `llm_rubric`。

新增或修改任务时，`hidden/expected`、judge 规则细节和 mock 应答库不要写进 `work/`、`samples/`、instructions、feedback 或 PR 描述。

## 真实工具和回放

工具默认走 `hidden/tools/<name>.responses.yaml`，保证 CI 零外呼和确定性。需要连接真实系统时，可在 `task.yaml` 声明 HTTP 后端：

```yaml
allowed_tools:
  - name: weather.lookup
    description: 查询城市天气
    backend:
      type: http
      url: https://api.example.com/weather
      method: POST
      headers:
        Authorization: 'Bearer ${ENV:WEATHER_API_KEY}'
      timeout_seconds: 10
```

运行模式由 `AEL_TOOL_MODE` 控制：

- `replay`：默认模式，只走应答库。
- `live`：真实外呼声明的 URL，并把响应存档为可晋升的回放库。

URL、method、headers 都由任务静态声明。Agent 入参只作为请求负载，不能改变外呼目标。

## 可观测和报告

单 run 报告：

```bash
bin/agent-eval report --run runs/xxx/run_yyy
```

离线复算：

```bash
bin/agent-eval judge --task tasks/xxx --submission sub.json
```

导出 trace 到 OTLP/OpenInference：

```bash
bin/agent-eval export --run runs/xxx/run_yyy
```

导出的 span 结构是 run -> attempt -> tool_call，可接入 OTel Collector、Phoenix、Langfuse 等看板。

## 安全边界

默认本地模式适合受控环境和合作式评测。它有 trace 签名、canary 扫描、工具 allowlist、hidden 指纹和红队门禁，但 Agent 与框架仍在同机同用户下运行。

对不可信或强对抗 Agent，请使用 `--sandbox docker`。Docker 模式已在 CI 中通过强制红队门禁：容器只挂载 Agent 可触碰区，外科式偷看 `hidden/`、symlink 逃逸和全盘 `find` 逃逸都会被挡住。

仍需注意：Docker 强度取决于宿主 Docker 配置和镜像可信度；`llm_rubric` 是低权重主观信号，不应用作一票否决。

更多细节见 [redteam/audit-report.md](redteam/audit-report.md) 和 [SECURITY.md](SECURITY.md)。

## AI 协作 Skills

仓库内置了项目级 skills，位置在 [.agents/skills](.agents/skills)。支持 Codex skills 的环境会原生发现；Claude Code 通过 [.claude/skills](.claude/skills) 符号链接复用同一套内容。

这些 skills 不是运行时依赖，而是给 AI 编码助手的工作流说明。你可以在对话里直接点名，例如“用 `ael-build-evalset` 帮我把业务场景做成评测集”。

| Skill | 什么时候用 | 主要产出 |
| --- | --- | --- |
| [`ael-build-evalset`](.agents/skills/ael-build-evalset/SKILL.md) | 从零建设私有测评集、接入真实 Agent、跑批量对比 | `evalsets/<set>/`、任务样例、`agents.yaml`、suite/history 命令 |
| [`ael-new-task`](.agents/skills/ael-new-task/SKILL.md) | 新增或修改单个任务（内置 `tasks/` 或私有 `evalsets/<set>/tasks/`） | `task.yaml`、`work/`、`hidden/`、`samples/` 和 fail→pass 回放闭环 |
| [`ael-review-task-quality`](.agents/skills/ael-review-task-quality/SKILL.md) | 审查任务能否进入 smoke/regression 门禁 | hidden 防泄露检查、样例闭环、判分稳定性和分层建议 |
| [`ael-analyze-results`](.agents/skills/ael-analyze-results/SKILL.md) | 评测跑完后分析 run/suite/history，定位为什么掉分或没过 | 基于 `report.json`、`suite_report.json`、trace 和 feedback 的证据化诊断 |
| [`ael-verify`](.agents/skills/ael-verify/SKILL.md) | 改完代码、任务、红队或文档后选择验证闸 | 最便宜但足够证明正确性的验证命令组合 |

推荐使用方式：

- 要评估自己的 Agent：从 `ael-build-evalset` 开始，不要把私有业务任务混进内置 `tasks/`。
- 要新增或修改单个任务（内置库或私有集都适用）：用 `ael-new-task`，并严格检查 hidden 防泄露。
- 要把任务放进 smoke/regression：用 `ael-review-task-quality` 做质量审查。
- 已经有评测结果但不知道问题在哪：用 `ael-analyze-results`。
- 准备提交前：用 `ael-verify` 选验证闸，再按需要跑 `bash bin/ci-smoke.sh`。

## 文档地图

- [docs/05-交互式导览.html](docs/05-交互式导览.html)：用户、产品、开发三视角的单文件交互导览。
- [docs/06-小团队落地指南.md](docs/06-小团队落地指南.md)：从私有 evalset、Agent 接入、suite 门禁到报告解读的落地路径。
- [docs/07-任务质量清单.md](docs/07-任务质量清单.md)：任务进入 smoke/regression 前的质量审查标准。
- [AGENTS.md](AGENTS.md)：AI 协作入口、架构分区、验证阶梯和安全红线。
- [docs/CODEMAP.md](docs/CODEMAP.md)：由源码生成的类级地图，CI 会检查漂移。
- [docs/PLAYBOOK.md](docs/PLAYBOOK.md)：新增 check、CLI、Agent adapter、任务、trace 事件等常见改造 recipe。
- [docs/03-AgentEval-Lite-设计方案.md](docs/03-AgentEval-Lite-设计方案.md)：完整架构与安全模型。
- [evalsets/demo-ops-agent/README.md](evalsets/demo-ops-agent/README.md)：从零搭建私有测评集的可运行示例。

## 贡献

欢迎 issue 和 PR。贡献前请先读 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [AGENTS.md](AGENTS.md)。

修改 Java 类、CLI 子命令、check 类型、trace 事件或任务后，请运行：

```bash
bash bin/gen-codemap.sh
mvn -q test
```

涉及 `judge`、`tool`、`trace`、Docker 沙箱、任务样例或红队用例的改动，还应运行：

```bash
bash bin/ci-smoke.sh
```

## 许可证

AgentEval-Lite 使用 Apache License 2.0。它允许商用、修改、分发和闭源集成，并包含明确的专利授权。详见 [LICENSE](LICENSE)。
