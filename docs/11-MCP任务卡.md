# MCP 环境适配器任务卡（索引）

> 设计依据：[docs/10-MCP环境适配器设计.md](10-MCP环境适配器设计.md)
> 卡片目录：[docs/tasks/](tasks/)，`TASK-MCP-00…10`，frontmatter 含 `dependsOn` / `risk` / `status` / `dodCommands`，兼容 `delivery` skill 的任务卡协议。
> 执行约定：合约（01）冻结后只实现不改；任何卡要动评测内核（SubmissionManager / JudgeRunner / FeedbackPolicy / RulesJudge 判分逻辑）视为设计错误，回 `docs/10` 重评。

## 卡片一览

| ID | 标题 | 里程碑 | 估时 | dependsOn | risk | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| [TASK-MCP-00](tasks/TASK-MCP-00.md) | Spike：SDK vs 手写定案 + 真实 client 握手 | MM0 | 0.5–1d | — | medium | 待办 |
| [TASK-MCP-01](tasks/TASK-MCP-01.md) | 合约冻结：7 工具 schema / 错误码 / 留痕 + WorkspacePathGuard | MM1 | 1d | 00 | medium | 待办 |
| [TASK-MCP-02](tasks/TASK-MCP-02.md) | McpAgentAdapter + SPI 钩子 + instructions/next_step 通道变体 | MM1 | 1–1.5d | 01 | high | 待办 |
| [TASK-MCP-03](tasks/TASK-MCP-03.md) | stdio 传输 + `mcp-serve` + stdout 纪律（get_task / submit / get_feedback 打通） | MM1 | 1d | 02 | high | 待办 |
| [TASK-MCP-04](tasks/TASK-MCP-04.md) | 完整工具面（workspace_* / call_tool）+ `agent_action` 留痕 | MM2 | 1–1.5d | 03 | medium | 待办 |
| [TASK-MCP-05](tasks/TASK-MCP-05.md) | 真实 client（Codex 仅评测 server）实跑与记录 | MM2 | 0.5d | 04 | medium | 待办 |
| [TASK-MCP-06](tasks/TASK-MCP-06.md) | 红队 M1–M8 + report `agent_actions` + SECURITY / README 边界 | MM3 | 1d | 04 | high | 待办 |
| [TASK-MCP-07](tasks/TASK-MCP-07.md) | 文档、client 配置示例、CHANGELOG 0.6.0 | MM3 | 0.5d | 05, 06 | low | 待办 |
| [TASK-MCP-08](tasks/TASK-MCP-08.md) | v2：Streamable HTTP + token + 多会话 | MM4 | 2d | 07 | high | 后置 |
| [TASK-MCP-09](tasks/TASK-MCP-09.md) | v2：suite 会话（`next_task`） | MM4 | 1d | 07 | medium | 后置 |
| [TASK-MCP-10](tasks/TASK-MCP-10.md) | v2：操作者工具面（物理分离） | MM4 | 1d | 08 | medium | 后置 |

v1（00–07）合计约 6.5–8 个工作日；v2 另计，不阻塞 0.6.0 发布。

## 执行顺序（tracer bullet 优先）

```text
00 spike ─► 01 合约 ─► 02 适配器/钩子 ─► 03 stdio+CLI（get_task+submit 打通真实 client）
                                            │
                                            ▼
                                   04 完整工具面 + 留痕
                                   ├─► 05 真实 client 实跑 ─┐
                                   └─► 06 红队 M 系列 ──────┴─► 07 文档 + 0.6.0
                                                                   │
                                                 v2: 08 HTTP ─► 10 操作者面 ；09 suite 会话
```

关键顺序理由：

- 00 先定依赖，避免 6 张卡返工。
- 01 先冻结合约，04 / 06 / 07 才能并行不打架。
- 03 只接 `get_task` + `submit` + `get_feedback` 就上真实 client——控制反转 + 同步反馈这条主链成立与否，是整个方案的成败点，必须最早验证。
- 05 与 06 都只依赖 04，可并行。

## 交给 AI 执行的约定（无歧义执行协议）

1. **先读三样再动手**：根 `AGENTS.md`（依赖方向与红线）→ `docs/10`（合约与硬约束，重点 §2.2 / §2.4 / §3.1 / §3.1a / §3.2）→ 本卡全文。卡片与 `docs/10` 冲突时以 `docs/10` 为准并在 PR 指出。
2. **卡片有效性核对**：卡片"影响模块 / 文件清单"里标为既有的路径必须存在；§1 引用的 `RunManager` 行号以 commit `d9653a0` 为基线，若已漂移，按语义（"写完 feedback 之后、cooldown 之前"等）定位而不是按行号硬套。
3. **TDD 顺序**：先写卡片 §9 列出的测试（RED），再实现（GREEN），每个 DoD 勾选项对应至少一个断言；测试命名沿用仓库中文方法名风格。
4. **不做卡外的事**：Out-of-scope 明确写死；发现相邻问题只记在 PR 描述"顺带发现"，不顺手改。
5. **遇到歧义**：优先按 `docs/10` 的判据裁决；裁决不了就停下来在 PR / 对话里提出两种读法及各自影响，不要猜一个做下去。
6. **完成判据**：`dodCommands` 逐条运行通过 + `bash bin/gen-codemap.sh --check`（凡新增类）+ `git diff --check`；安全敏感面（trace / tool / PathGuard / 红队）额外 `bash redteam/test_gate.sh && bash redteam/run_all.sh`。
7. **回写状态**：完成后把本卡 frontmatter `status` 改 `done`、本索引状态列改"完成"，并在 `docs/10` 相应章节补"已实施"或决策记录。

## 整体优先于局部的检查项（每张卡完成时自问）

1. 有没有动评测内核？动了就停。
2. 有没有改合约？改了回 01 更新快照与 `docs/10` §2.4。
3. 有没有给 v2 的东西提前挤进 v1（HTTP、resources、prompts、进度通知、多会话）？有就拿出去。
4. 新增的 trace / report 字段是否只认签名事件？适配器是否只用了 `onRunStarted` 交来的那一把 TraceLogger？
5. 文档写的隔离前提是否仍然成立（Agent 只有评测 server 一个工具面）？

## 里程碑判据

| 里程碑 | 判据 |
| --- | --- |
| MM0 | `docs/10` §2.6 有决策段；Codex 与 SDK client 握手通过 |
| MM1 | SDK client e2e：api-payload-001 fail→pass 全经 `submit`（含一次 `pending` → `get_feedback` 路径）；Codex 仅配评测 server 能列出工具并完成一次 submit |
| MM2 | tool-call-001 经 `call_tool` 通过；`agent_action` 进 trace 且 seq 连续；`mvn verify` 全绿 |
| MM3 | 红队 M1–M8 DEFENDED，基线 0；report 有 `agent_actions`；`bash bin/ci-smoke.sh` 全绿；0.6.0 CHANGELOG |
| MM4 | v2 三卡各自验收，不影响 v1 行为 |

## 评审

设计初稿经独立评审（Grok，2026-09-16）"有条件同意"，10 条修改已吸收，见 `docs/10` 附录 B。
