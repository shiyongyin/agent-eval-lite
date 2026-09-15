---
id: TASK-USE-03
title: 提供可运行 Agent CLI 的 Docker 沙箱镜像配方
module: docker / docs
dependsOn: []
risk: medium
featureFlag: none
dodCommands:
  - docker build -f docker/agent-cli.Dockerfile -t ael-agent-cli .
  - bin/agent-eval run --task tasks/api-payload-001 --agent cli --sandbox docker --sandbox-image ael-agent-cli --sandbox-network bridge --sandbox-docker-arg '-e ANTHROPIC_API_KEY' --cmd 'bash "$AEL_RUN_DIR/../../evalsets/_template/scripts/run-agent.sh" claude' --runs-root runs/dogfood
  - git diff --check
---

# TASK-USE-03：提供可运行 Agent CLI 的 Docker 沙箱镜像配方

## 0. Meta
- 语言/框架版本：Dockerfile；Java 17 CLI 已有 `--sandbox docker` 选项
- 影响模块/包前缀：新增 `docker/agent-cli.Dockerfile`；`README.md`、`docs/06`、`SECURITY.md`
- 最小验收命令：`docker build` + 一次 `--sandbox docker` run

## 1. 背景
- 现状：`--sandbox-image` 描述为“须自带 Agent 命令所需环境”；CI 红队用 `alpine` 固定 digest；仓库没有任何能跑 Agent CLI 的镜像示例。
- 痛点：“不可信 Agent 必须 Docker”只是文档承诺，用户拿默认 `alpine` 跑不了 Claude Code / Codex。

## 2. 目标（Definition of Done）
- [ ] `docker/agent-cli.Dockerfile` 可构建，含 `claude`、`codex`、`bash`、`jq`、`curl`，非 root 运行
- [ ] 镜像不含任何凭证；不 `COPY` 仓库文件
- [ ] `README.md` cli 一节与 `docs/06` 接入一节补“构建镜像 → `--sandbox docker` 跑一次”标准命令
- [ ] `SECURITY.md` 或 README 安全边界写明：凭证经 `--sandbox-docker-arg -e` 透传；需模型 API 时须显式 `--sandbox-network bridge` 并接受该边界
- [ ] 红队 `bash redteam/run_all.sh` 行为不变

## 3. 范围
### In-scope
- `docker/agent-cli.Dockerfile`
- `README.md`、`docs/06-小团队落地指南.md`、`SECURITY.md`
### Out-of-scope
- 修改 `.github/workflows/ci.yml` 中 `RT_SANDBOX_IMAGE` 及 digest
- 发布镜像到任何 registry
- 修改 `DockerSandbox` / `DockerAgentAdapter`

## 4. 约束与关键决策
- 基础镜像 node LTS slim；`npm i -g @anthropic-ai/claude-code @openai/codex`
- `--sandbox-network none` 仍为默认；文档中 bridge 仅作为需要模型 API 时的显式选择
- 隔离回归（alpine）与 Agent 运行环境（本镜像）是两件事，互不替代

## 6. Failure Modes & Safeguards
- 镜像内 CLI 版本漂移 → Dockerfile 用 `ARG` 固定版本并注释升级方式
- 用户忘传凭证 → Agent 非零退出，框架记无提交；文档给出排查路径 `agent-logs/`

## 7. 可观测性 & 运维
- 排障路径：`runs/<task>/<run>/agent-logs/attempt_NNN.log`

## 8. 代码改动点
- 文件清单：见 In-scope
- 配置项：`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` 经 `--sandbox-docker-arg -e` 透传

## 9. 测试策略
- 最小集：`docker build`；一次 `--sandbox docker` run 产生 inbox 提交
- 推荐回归：Docker 就绪时 `bash redteam/run_all.sh` 仍 19 项 DEFENDED

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：镜像构建成功；run 产生 `inbox/attempt_001.json`；`docker history ael-agent-cli` 无 `*_API_KEY`

## 11. 风险与回退
- 风险：镜像体积大、拉取慢 → 仅文档示例，不进 CI
- 回退：删除 Dockerfile 与文档段
