---
id: TASK-USE-06
title: 自包含 HTML 报告（suite_report.html / report.html / history.html）
module: report / runner / cli
dependsOn: [TASK-USE-04]
risk: medium
featureFlag: none
dodCommands:
  - bash bin/gen-codemap.sh --check
  - mvn -q verify
  - bin/agent-eval suite --tasks-root tasks --fail-on-not-passed
  - "! rg -l private_notes runs/suite/*/suite_report.html runs/*/*/report/report.html"
---

# TASK-USE-06：自包含 HTML 报告

## 0. Meta
- 语言/框架版本：Java 17 / Maven；HTML + 原生 JS（无构建、无依赖）
- 影响模块/包前缀：`com.agenteval.report`（新增 `HtmlRenderer`）、`runner/SuiteRunner`、`cli/HistoryCommand`、`src/main/resources/report/`
- 最小验收命令：`mvn -q verify`

## 1. 背景
- 现状：`suite_report` / `report` / `history` 各有 `.json` + `.md`；`docs/05-交互式导览.html` 已是零依赖单文件先例。
- 痛点：PM / QA / 负责人不开终端看不懂 Markdown；`docs/04` 已否决带服务端的看板。

## 2. 目标（Definition of Done）
- [ ] 新增 `report/HtmlRenderer.java`：纯函数 JSON → HTML 字符串；模板在 `src/main/resources/report/{suite,run,history}.html`
- [ ] `ReportGenerator`、`SuiteRunner`、`HistoryCommand` 各调用一次，与 `.md` 同目录输出 `.html`
- [ ] JSON 以 `<script type="application/json" id="data">` 内联，页面 JS 只渲染；无外链 CSS / JS / CDN
- [ ] `suite_report.html`：模式识别、任务 × Agent 矩阵（pass^k 着色）、`risk_summary` / 失败规则热点、每格链接相对路径 `report.html`
- [ ] `report.html`：`run` 摘要、`score_trajectory`、`best_attempt.dimension_breakdown`、逐 attempt `failed_rules` + 对外 feedback、`tool_usage` / `safety` / `cost`
- [ ] `history.html`：`trends` 表 + 分数走势
- [ ] 所有来自 JSON 的字符串 HTML 转义
- [ ] 测试断言：HTML 存在、内联 JSON 与 `.json` 一致、不含 `private_notes`、不含 `http://` / `https://` 资源引用
- [ ] `README.md` 报告一节、`docs/06` “看报告”一节更新；`docs/CODEMAP.md` 重新生成

## 3. 范围
### In-scope
- `src/main/java/com/agenteval/report/HtmlRenderer.java`、`src/main/resources/report/*.html`
- `report/ReportGenerator.java`、`runner/SuiteRunner.java`、`cli/HistoryCommand.java`
- 测试：`ReportGeneratorTest`、`SuiteRunnerTest`、`HistoryCommandTest`
- `README.md`、`docs/06-小团队落地指南.md`
### Out-of-scope
- 服务端 / `agent-eval ui` 命令
- 读取 `judge/*.judge.json`、`hidden/`、`traces/`
- 新增任何报告字段

## 4. 约束与关键决策
- HTML 是三份公开 JSON 的视图；数据边界与 `.md` 完全一致。
- `private_notes` 出现在 HTML 即视为 hidden 泄露，测试钉死。
- 依赖方向：`runner -> report`、`cli -> report`；`HtmlRenderer` 不依赖 `runner`。

## 6. Failure Modes & Safeguards
- JSON 中字段缺失（旧 run）→ 页面显示 `—`，不报错
- feedback 文案含 `<script>` → 转义后原样显示
- 模板资源加载失败 → 抛 `IllegalStateException`，`.json` / `.md` 已先写出不受影响

## 8. 代码改动点
- 文件清单：见 In-scope
- 配置项：无

## 9. 测试策略
- 最小集：三个报告测试各加 HTML 断言（存在 / 内联一致 / 无 `private_notes` / 无外链）
- 推荐回归：浏览器 `file://` 打开一份 TASK-USE-01 真实 dogfood 的 `suite_report.html`

## 10. 验收标准
- 命令：见 `dodCommands`
- 期望结果：全部 exit 0；`rg -l private_notes` 无匹配（`!` 取反成立）

## 11. 风险与回退
- 风险：页面需求膨胀成看板 → 守住“只读三份 JSON、不新增字段、无服务端”
- 回退：删除 `HtmlRenderer` 与三处调用，`.json` / `.md` 不受影响
