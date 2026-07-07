# 代码层指南（src/main/java/com/agenteval）

先看 `docs/CODEMAP.md`（生成的类级地图）定位类，再回到这里查接线位置与不变量。包职责与依赖方向见根 `AGENTS.md`「60 秒理解」。

## 扩展点接线表（改哪类东西，按序碰哪些文件）

| 扩展点 | 按序接线 | 验证 |
| --- | --- | --- |
| 新 check 类型 | `judge/RulesFile.SUPPORTED_TYPES` 注册 → `judge/RulesJudge#check` 的 switch 加分支与实现 → 若有「run 时才炸」的静态可查错误，在 `cli/ValidateCommand#lintRules` 加深度 lint → `README.md` check 清单补一句 | `RulesJudgeTest` / `RulesJudgeHardeningTest` 加用例；`ValidateLintTest`（若加了 lint） |
| 新 Agent 适配器 | `agent/` 实现 `AgentAdapter`（子进程型复用 `AgentProcess`）→ `cli/RunCommand#buildAdapter` 的 switch → `runner/SuiteRunner.AgentSpec` 加工厂 → `cli/SuiteCommand`（`--agent` 枚举 + `parseAgentsFile` 的 type 分支）→ `README.md`「Agent 接入方式」 | `integration/` 加端到端（参考 `HttpAgentRunTest`） |
| 新 trace 事件 | `trace/TraceEventType` 加枚举（带 Javadoc 注释语义）→ 在产生该事实的组件调 `TraceLogger` 落事件 → 若需专属 span 语义，`trace/OtlpTraceExporter#build` 的 switch 加分支（不加则走 default 的通用 event 附着） | `TraceLoggerTest` / `OtlpTraceExporterTest` |
| 新 CLI 子命令 | `cli/XxxCommand`（picocli，实现 `Callable<Integer>`）→ `cli/Main` 的 `subcommands` 注册 → `README.md`「其余命令」 | 对应 `cli/*Test` 或 `integration/` |
| 新工具后端类型 | `task/TaskSpec.AllowedTool.backend` 结构 → `tool/ToolGateway` 分派 → 参考 `tool/ToolHttpBackend`（live 录制契约）| `ToolBackendLiveReplayTest` |

## 跨包不变量（改动前必读，破坏即引入安全/复现回归）

1. **评分可复现**：`JudgeInput` 是只读视图，judge 实现禁止读取视图之外的可变状态；同输入必同分。`command` 型 check 只在 workspace 的**临时副本**上执行（ephemeral，评审不污染现场）。
2. **trace 可信链**：签名密钥在 Agent 运行期间仅存内存——`TraceSecret` 只在 run 收尾（Agent 已停止）落盘 `.ael/trace.key`，resume 读出后**立即删除**；工具轨迹类检查（`tool_call_required`/`world_state`）只统计 HMAC 可核验事件。任何「提前落盘密钥」「放宽签名校验」的改动都是安全回归。
3. **command 防短路**：judge 每轮生成随机 nonce 注入命令模板 `{nonce}` 与 `output_regex`，成功标记必须含本轮 nonce。改 `RulesJudge#command` 后必须跑红队 D/D2。
4. **工具 fail-closed**：`ToolGateway` 按任务 `allowed_tools` 白名单拒绝越权调用；replay 模式下无应答库的后端工具调用失败而非外呼。URL/method/headers 静态声明，Agent 入参改不了外呼目标。
5. **提交唯一通道**：只有 `inbox/attempt_NNN.json` 计分，信封+分型双重 schema 校验（`SubmissionManager`）；自然语言输出不计分。
6. **fail-fast 规格**：`TaskSpecLoader` 聚合全部校验错误一次性抛 `TaskSpecException`，配错的任务不允许起跑。
7. **依赖方向**：`util` 不依赖任何兄弟包；域包不依赖 `runner`/`cli`；违反即架构回归（可用根指南的分层表自查）。
8. **退出码契约**：0 正常完成（无论通过与否）/ 1 参数或输入错误 / 2 框架故障或完整性熔断 / 3 配了 `--fail-on-not-passed` 且未通过。新命令沿用同一口径。

## 测试地图

被测包与测试同路径对应（如 `judge/RulesJudge` → `judge/RulesJudgeTest`）；端到端在 `integration/`：`EndToEndScriptedRunTest`（主闭环）、`ResumeEndToEndTest`（断点续跑）、`DockerSandboxRunTest`（容器沙箱，无 Docker 自动跳过）、`SuiteRunnerTest`（批跑）、`TasksValidationTest`（任务库体检）。共享夹具在 `testsupport/TestSpecs`。
