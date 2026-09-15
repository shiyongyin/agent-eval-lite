---
id: TASK-USE-00
title: 提交待收口的 research.md 与 ael-analyze-results 改动
module: docs / .agents/skills
dependsOn: []
risk: low
status: done
featureFlag: none
dodCommands:
  - git diff --check
  - bash .agents/skills/ael-analyze-results/scripts/summarize-suite.sh "$(ls -t runs/suite/*/suite_report.json | head -1)"
---

# TASK-USE-00：提交待收口的 research.md 与 ael-analyze-results 改动

## 0. Meta
- 语言/框架版本：文档 + bash（需 `jq`）
- 影响模块/包前缀：`research.md`、`.agents/skills/ael-analyze-results/`
- 最小验收命令：`git diff --check`

## 1. 背景
- 现状：`research.md` 已重写为加固清单（07-09）；`ael-analyze-results` 精简版 SKILL.md、新增 `REFERENCE.md` 与 `scripts/summarize-suite.sh` / `summarize-run.sh`（07-14）均未提交。
- 痛点：已验证可用的产物在工作区滞留两个月，后续任务卡（R1 依赖分析脚本）建在未提交内容上。

## 2. 目标（Definition of Done）
- [ ] 上述文件以一次 commit 入库，工作区无这些文件的未提交改动
- [ ] `.claude/skills/ael-analyze-results/REFERENCE.md` 经符号链接可见

## 3. 范围
### In-scope
- `research.md`
- `.agents/skills/ael-analyze-results/SKILL.md`、`REFERENCE.md`、`scripts/*.sh`
### Out-of-scope
- 修改上述文件内容
- `docs/08`、`docs/09`、`docs/tasks/`（另行提交）

## 4. 约束与关键决策
- `.claude/skills -> ../.agents/skills` 为符号链接，无需同步副本。
- 提交信息建议：`收口 ael-analyze-results 精简版与外部框架校准加固清单`。

## 6. Failure Modes & Safeguards
- 脚本对最新 suite 报告执行失败 → 不提交，先修脚本。

## 8. 代码改动点
- 文件清单：无新增；仅 `git add` 上述路径

## 9. 测试策略
- 最小集：`summarize-suite.sh` → `--drill` → `summarize-run.sh` 对真实 `runs/suite` 跑通

## 10. 验收标准
- 命令：`git status --short` 中不再出现上述路径；`git diff --check` 无输出
- 期望结果：exit 0

## 11. 风险与回退
- 风险：无
- 回退：`git revert <commit>`
