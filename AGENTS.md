# 仓库指南（AI 渐进式入口）

AgentEval-Lite 是企业内部 AI Agent 测试脚手架：零外部服务、单 jar、目录即隔离。核心信任模型是 Work/Judge 隔离、结构化提交、隐藏判分、HMAC trace、可复现报告。

## 先路由，不全量加载

AI 进入仓库时只读本文件。命中具体任务后，只加载下表命中的材料；不要一上来读完整 docs、全部源码或全部任务目录。

| 任务 | 下一步只读 |
| --- | --- |
| 理解/修改 Java 代码 | `docs/CODEMAP.md` 定位类；涉及扩展点再读 `src/main/java/com/agenteval/AGENTS.md` |
| 新增 CLI / check / adapter / trace / 红队 | `docs/PLAYBOOK.md` 对应 recipe |
| 新增或修改内置任务 `tasks/<id>` | `tasks/AGENTS.md` + `.agents/skills/ael-new-task/SKILL.md` |
| 建小团队私有测评集 `evalsets/<set>` | `.agents/skills/ael-build-evalset/SKILL.md` + `docs/06-小团队落地指南.md` |
| 审查任务能否进 smoke/regression | `.agents/skills/ael-review-task-quality/SKILL.md` + `docs/07-任务质量清单.md` |
| 分析 run/suite/history 结果 | `.agents/skills/ael-analyze-results/SKILL.md` |
| 选择验证命令 | `.agents/skills/ael-verify/SKILL.md` |
| 红队攻防 | `redteam/AGENTS.md` |
| 理解设计背景 | `README.md`；必要时再读 `docs/03-AgentEval-Lite-设计方案.md` / `docs/04-成熟评估框架调研与ROI报告.md` |

## 小团队 + AI 辅助默认路径

用户说“先补基础设置”“建小团队测评集”“生成任务”但还没有完整业务题库时，走这条路径：

1. 用 `ael-build-evalset`，通过 `bin/agent-eval evalset init --id <set>` 或 `evalsets/_template/` 建集合骨架。
2. 用 `ael-new-task`，通过 `bin/agent-eval task init --tasks-root evalsets/<set>/tasks` 生成任务脚手架。
3. 任务进门禁前用 `ael-review-task-quality` 审查。
4. 改完用 `ael-verify` 选择最小验证闸。

不要从空目录手写任务结构；不要把私有业务任务混进内置 `tasks/`。

## 必守边界

- 依赖方向保持单向：`cli -> runner -> {agent, judge, submission, tool, trace, report, state, workspace} -> task -> util`。
- `hidden/` 是评审禁区：expected、judge 私有规则、mock 响应不得复制进 `work/`、`samples/`、instructions、feedback、公开文档或 PR 描述。
- trace 可信链不能放松：签名密钥在 Agent 运行期间只在内存；工具/终态判分只认 HMAC 可核验事件。
- 生成物不入库：`target/`、`runs/`、`evalsets/*/runs/` 不提交。
- `docs/CODEMAP.md` 只能由 `bash bin/gen-codemap.sh` 生成，不手改。

## 验证

改动后用 `.agents/skills/ael-verify/SKILL.md` 选择最小验证闸。文档-only 改动至少跑 `git diff --check`；涉及 `docs/CODEMAP.md` 路由或生成规则时加 `bash bin/gen-codemap.sh --check`。
