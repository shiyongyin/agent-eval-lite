---
id: TASK-USE-05
title: evalset CI 工作流模板（PR 自动跑 smoke 门禁）
module: evalsets/_template / cli
dependsOn: [TASK-USE-02]
risk: low
featureFlag: none
dodCommands:
  - mvn -q -Dtest=EvalsetInitScaffoldTest test
  - python3 -c "import yaml,sys; yaml.safe_load(open('evalsets/_template/ci/evalset-smoke.yml'))"
  - git diff --check
---

# TASK-USE-05：evalset CI 工作流模板（PR 自动跑 smoke 门禁）

## 0. Meta
- 语言/框架版本：GitHub Actions YAML；Java 17 测试 `EvalsetInitScaffoldTest`
- 影响模块/包前缀：`evalsets/_template/ci/`、`cli/EvalsetCommand.java`、`docs/06`
- 最小验收命令：YAML 可解析 + `EvalsetInitScaffoldTest`

## 1. 背景
- 现状：`.github/workflows/ci.yml` 只守框架自身；团队 evalset 没有现成的 PR 门禁。
- 痛点：框架把 Agent 退化挡在合并前的能力没有交付形态。

## 2. 目标（Definition of Done）
- [ ] 新增 `evalsets/_template/ci/evalset-smoke.yml`
- [ ] 步骤：checkout → JDK 17 → `mvn -q -DskipTests package` → `validate` 全部任务 → `suite --tier smoke --agents-file agents.yaml --repeat 2 --fail-on-not-passed` → 上传 `suite_report.*`
- [ ] scripted 基线行永远跑；需真实模型的 job `if: github.event_name == 'workflow_dispatch'`
- [ ] 注释给出两种布局（Agent 与 evalset 同仓 / 异仓）与 Docker 强制写法（参照 `ci.yml` 的 `redteam-docker-enforced`）
- [ ] 凭证只以 `${{ secrets.XXX }}` 形式出现
- [ ] `evalset init` 一并复制该文件；`EvalsetInitScaffoldTest` 断言
- [ ] `docs/06` “推荐门禁”一节与 `evalsets/_template/README.md` 目录表更新

## 3. 范围
### In-scope
- `evalsets/_template/ci/evalset-smoke.yml`、`evalsets/_template/README.md`
- `src/main/java/com/agenteval/cli/EvalsetCommand.java`、`src/test/java/com/agenteval/cli/EvalsetInitScaffoldTest.java`
- `docs/06-小团队落地指南.md`
### Out-of-scope
- 在本仓库 `.github/workflows/` 新增 job
- 任何密钥、任何 registry

## 4. 约束与关键决策
- 模板放 `evalsets/_template/ci/` 而非 `.github/`，避免在本仓库触发。
- 默认不花钱：模型 job 手动触发；scripted 基线保证“任务本身没坏”。

## 6. Failure Modes & Safeguards
- 用户仓库没有 Docker → 模板默认不要求 Docker，Docker 强制段为注释示例
- `--tier smoke` 无任务 → suite 退出码非 0，模板注释说明

## 8. 代码改动点
- 文件清单：见 In-scope
- 配置项：`secrets.ANTHROPIC_API_KEY` 等（仅示例引用）

## 9. 测试策略
- 最小集：`EvalsetInitScaffoldTest`；YAML 解析
- 推荐回归：在一个临时仓库手动触发一次跑通，记录到 `docs/08` 附录

## 10. 验收标准
- 命令：`bin/agent-eval evalset init --id tmp-x --tasks-root /tmp && ls /tmp/tmp-x/ci/evalset-smoke.yml`（按实际 init 参数调整）
- 期望结果：文件存在；`actionlint`（若有）无 error

## 11. 风险与回退
- 风险：用户在 PR 上误触真实模型费用 → 默认手动触发
- 回退：删除模板与 init 复制逻辑
