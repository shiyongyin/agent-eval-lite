# Changelog

## 0.5.0 - 2026-09-16

面向"投入使用"的一轮（路线与任务卡见 `docs/08`、`docs/09`），全部改动都经真实 Agent（Codex CLI）实跑验证。

### 新增

- `run --label`：Agent 标签进入 `meta.json`（`agent_name`）、`report.json`（`run.agent`）、trace `run_started` 与 OTLP 根 span；`meta.json` / `report.json` 新增 `agent_adapter` / `run.adapter` 区分接入形态。`history` 因而按 label 分行，current vs candidate 不再合并成一行 `cli`。
- 自包含 HTML 报告：`report.html`、`suite_report.html`、`history.html` 与同目录 JSON / Markdown 一起产出；数据内联、零外链、不读 `judge/` 与 `hidden/`，矩阵格子可点进 run 报告。
- evalset 脚手架：`scripts/run-agent.sh` 内置 `claude` / `codex` / `custom` 三个 profile，自动把上一轮反馈的对外字段拼进 prompt；新增 `ci/evalset-smoke.yml` GitHub Actions 模板（scripted 基线每 PR 跑，真实 Agent 手动触发）。模板改为 classpath 资源，与 `evalsets/_template/` 内容一致并有测试守护。
- `docker/agent-cli.Dockerfile`：可在 `--sandbox docker` 中运行 Claude Code / Codex CLI 的镜像配方（含 JRE、`ael-run-agent`、容器内 `agent-eval` 垫片，不含凭证）。
- suite `risk_summary.pending_human_tasks`：Agent 标 `needs_human_review` 的任务单列，不计通过；文档补人工复核留痕路径。
- cli 适配器在 run 目录 `.ael/bin/` 放 `agent-eval` 垫片并前置 PATH，兑现 instructions 里的 `agent-eval tool call` 调用方式。

### 修复

- 子进程 stdin 是一直挂着的管道，`codex exec` 等 EOF 直到超时；现启动后立即关闭 stdin。
- 反馈 `next_step` 给相对路径 `inbox/attempt_002.json`，真实 Agent 按 cwd 解析写进 `workspace/inbox/`；现给绝对路径，且无提交时点名 workspace 内错位的同名文件。
- `evidence_sources_valid` 只认 `work/docs/x` 写法，Agent 按题面引用 `docs/x` 白丢分；现工作区内真实文件的相对引用一律有效，绝对路径与 `..` 逃逸仍拒绝。
- `ScriptJudge` 用管道 `readAllBytes` 再 `waitFor(timeout)`，超时形同虚设；现输出落临时文件、按预算强杀。
- `prd-review-001`、`tool-call-001` 轮次超时 5 → 10 分钟（推理型模型 5 分钟写不完长输出）。

### 文档

- `docs/08` 投入使用路线设计（含 R1 实跑记录附录）、`docs/09` 任务卡索引与 `docs/tasks/TASK-USE-00…09`。
- README / SECURITY：`usage` 自报不参与评分、OTLP 导出是观察副本、`--label` 不参与判分、Docker 接入要点（`--sandbox-docker-arg` 一个 token 一个参数）。
- `tasks/AGENTS.md`、`ael-new-task`：`world_state` 的 `scope` / `order_sensitive` / 多重集语义。
- `docs/07`：按真实模型耗时定超时；题面路径写法与判分器保持一致。

## 0.2.0 – 0.4.0（2026-07-07 同日落地，未单独发布）

按 `docs/03` 路线图 Phase 2–4 逐项落地，细节见 `docs/04` §12–13 与 `redteam/audit-report.md` §11：HTTP AgentAdapter、`task init` 脚手架与 `validate` 深度 lint、真实工具 http 后端（live 录制 → replay）、auto-eval 后台采样、`llm_rubric` 低权重 LLM 判分及框架护栏、`--sandbox docker` 容器隔离、`suite --agents-file` 多 Agent 对比、`history`、OTLP/OpenInference 导出、红队 fail-closed 门禁与 CI、`evalset init` 私有测评集脚手架。

## 0.1.0 - 2026-07-07

- Initial public-ready release preparation.
- Adds CLI evaluation flow, task specs, structured submissions, hidden judges, trace signing, reports, suite runs, red-team gates, HTTP agent support, live/replay tools, LLM rubric guardrails, and Docker sandbox support.
