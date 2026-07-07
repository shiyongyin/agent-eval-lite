# 任务库指南（tasks/）

一个任务 = 一个可复现的评估单元。完整编写流程见 `docs/PLAYBOOK.md` recipe 4；check 类型全集与计分规则见根 `README.md`「写一个新任务」。

**本目录是框架内置自测库**（进 CI 冒烟门禁与 `TasksValidationTest`）：只放框架自证用的示例任务。用户自己的业务测评集放独立的 `evalsets/<set>/`（本指南的红线与三件套要求同样适用），建集工作流见 skill `ael-build-evalset` 与示例 `evalsets/demo-ops-agent/`。

## 目录契约

```text
tasks/<task-id>/
├── task.yaml            # 规格单一事实来源；task_id 必须等于目录名（kebab-case + 三位数字，如 code-fix-001）
├── work/                # Agent 可见材料（run 时复制为私有 workspace）
├── hidden/              # 评审禁区：judge.rules.yaml、expected/、tools/*.responses.yaml、评分脚本
└── samples/             # attempt-pass.json + attempt-fail.json + replay.yaml（任务自测三件套）
```

从脚手架起步而不是从空目录猜：`bin/agent-eval task init --id my-task-001` 产出开箱即过 validate 与 fail→pass 回放闭环的最小任务。

## task.yaml 硬约束（validate 会拦，但先知道少走弯路）

- `task_id` == 目录名；YAML 字段一律 snake_case。
- `scoring.dimensions` 权重之和必须等于 `max_score`；每个 check 的 `dimension` 必须已声明。
- `tier` ∈ smoke / regression / security / domain（批跑过滤元数据，不影响判分）。
- 深度 lint 前移的错误：`expected_from` 断链、`schema_file` 缺失、check 引用 `allowed_tools` 白名单外的工具、`llm_rubric` 有效权重 >30% 或设 blocking。

## hidden 防泄露检查单（安全红线，逐条自查）

1. 期望值只放 `hidden/expected/`，规则里用 `expected_from: "expected/x.json#/指针"` 引用，绝不写进 `task.yaml` 对外字段。
2. `feedback_fail` 是回传 Agent 的对外文案：只说「哪里不对」，禁含期望值、hidden 路径或可推导答案。
3. `work/`、`samples/`、`agent_brief` 里不得出现 hidden 内容的任何拷贝（包括改写复述）。
4. mock 应答库放 `hidden/tools/<name>.responses.yaml`；真实后端凭证用 `${ENV:*}` 从框架进程环境解析，永不落任务文件。
5. 提交 PR 前 diff 自查一遍上述四条——canary 探针只能抓「整段照抄」，抓不住无意识的改写泄露。

## 验证闭环（新任务的完成判据）

```bash
bin/agent-eval validate --task tasks/<task-id>                       # 静态体检 + 深度 lint
bin/agent-eval run --task tasks/<task-id> --agent scripted \
    --script tasks/<task-id>/samples/replay.yaml                     # fail→pass 回放闭环跑通
bin/agent-eval suite --tasks-root tasks --fail-on-not-passed         # 不破坏任务库整体门禁
mvn -q test                                                          # TasksValidationTest 等随套件回归
```

replay.yaml 应编排「第 1 轮失败 → 按反馈修正 → 第 2 轮通过」的完整闭环，让多轮受控反馈机制被真实演练。
