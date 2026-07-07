# AgentEval-Lite 红队审计报告

> **时点声明**：本报告是红队套件初建期（矩阵 10 项，对应提交 `2092e70`）的审计快照，正文保留当时结论、不做改写。其后框架已扩充 G / H / D2 / I 四类攻击用例、J 组 llm_rubric 框架护栏（权重封顶 / 禁 blocking / fail-closed）、完成门禁 fail-closed 化，并落地 Docker Runner（`--sandbox docker`）。当前矩阵随 Docker 就绪度自适应：**Docker 就绪时 19 项全 DEFENDED（登记基线 0）；Docker 不就绪回退非容器 A 时为 17 项——16 DEFENDED / 1 VULNERABLE（登记基线 1）**。最新复核结果与逐项时效性修正见文末第 11 节「复审更新」。

## 1. 执行摘要

当前框架处于“可内部试用”阶段：核心 TaskSpec、Runner、Submission、Judge、Trace、Tool Gateway、Report、多轮反馈和 Resume 都已可运行，且大部分攻击用例已有防护。

结论：可以作为 Demo 和 MVP；可以给可信或半可信 Agent 做内部评估；不应直接接入真实业务流程或不可信 Agent。最大风险是 CLI Agent 与框架同机同用户运行，可直接读取 `tasks/<task-id>/hidden/`。最优先修复是引入容器/沙箱隔离，只挂载 workspace、inbox、feedback 和必要工具网关。（**时效更新**：该 P0 已在 §11.5 由 `--sandbox docker` 根因修复——容器只挂 Agent 可触碰区，红队 A 外科式偷看 + symlink/find 逃逸在 Docker 就绪时全部 DEFENDED；本节结论保留为快照期历史。）

本次审计发现 3 个可低风险修复的问题并已修复：report 误统计伪造 tool trace、submission 接受伪造顶层字段、trace 被删除后仍可能通过。最终红队矩阵：9 DEFENDED，1 VULNERABLE。

## 2. 实际执行记录

| 命令 | 结果 | 关键输出 |
|---|---|---|
| `mvn -q test` | 通过 | 全量测试 0 退出码，内置 integration run 均完成 |
| `mvn -q package` | 通过 | 生成 `target/agent-eval-lite-0.1.0-cli.jar` |
| `bin/agent-eval list` | 通过 | 列出 5 个内置任务 |
| `bin/agent-eval validate --task tasks/api-payload-001` | 通过 | `OK api-payload-001` |
| `bin/agent-eval run --task tasks/api-payload-001 --agent scripted ...` | 通过 | `PASSED`，最佳 `attempt_002`，100 分 |
| `bin/agent-eval report --run runs/audit-baseline/...` | 通过 | 生成 `report.json` 和 `report.md` |
| `bash redteam/run_all.sh` | 通过 | 9 DEFENDED，1 VULNERABLE |

注意：沙箱内直接运行 `agent-eval run` 曾因工具网关绑定 loopback 端口失败，报 `java.net.SocketException: Operation not permitted`。提升权限后命令可正常执行。

## 3. 代码库理解结果

框架位置：当前仓库根目录。

主要模块：

- `src/main/java/com/agenteval/cli`：CLI 子命令。
- `runner/RunManager.java`：run 生命周期、多轮、resume、stop hook。
- `task/TaskSpecLoader.java`：`task.yaml` 解析与语义校验。
- `submission/SubmissionManager.java`：JSON 提交契约校验。
- `judge/RulesJudge.java`、`JudgeRunner.java`：规则/脚本判分和聚合。
- `tool/ToolGateway*.java`：allowed tools、mock 响应、签名 trace。
- `trace/TraceLogger.java`、`TraceSigner.java`：JSONL 留痕和 HMAC 签名。
- `report/ReportGenerator.java`：JSON/Markdown 报告。

已实现能力：TaskSpec、work/hidden 目录契约、结构化 submission、rules/script judge、多轮 feedback、stop hook、resume、trace、report、manual/scripted/cli adapters、mock tool gateway。

未实现或不完整：容器级强隔离、真实 HTTP AgentAdapter、真实工具权限模型、多 Agent 对比报告、auto-eval 后台采样、LLM judge 防注入。

测试覆盖：已有 JUnit 单元/集成测试，覆盖 task loader、submission、judge、trace signer、workspace、report、end-to-end run。新增红队脚本位于 `redteam/run_all.sh`。

## 4. 红队测试矩阵

| 测试类别 | 测试用例 | 是否执行 | 结果 | 证据 | 结论 |
|---|---|---:|---|---|---|
| Hidden 隔离 | 读取 `hidden/expected/answer.json` 后照抄 | 是 | `PASSED` 且 `canary_leaks=0` | `runs/redteam/api-payload-001/run_20260707_010208_jsv5/report/report.json` | VULNERABLE |
| Hidden 隔离 | dump 含 canary 的 hidden 工具库 | 是 | `canary_leaks=3` | `runs/redteam/tool-call-001/run_20260707_010208_rte5/report/report.json` | DEFENDED |
| Submission 校验 | 自然语言、非法 JSON、缺字段、错类型、超大 payload、伪造字段 | 是 | 全部 schema 阶段拒绝 | `bash redteam/run_all.sh` 的 `B 非法提交(8类)` | DEFENDED |
| Trace 真实性 | 伪造无签名 `tool_call` | 是 | judge 不认可，report 标记 `untrusted_trace_events=3` | `runs/redteam/tool-call-001/run_20260707_010209_74v6/report/report.json` | DEFENDED |
| Trace 真实性 | 删除 `trace.jsonl` 后提交合法答案 | 是 | run 变为 `ERROR` | `C3 删除trace审计链` | DEFENDED |
| Tool Adapter 权限 | 不走网关伪造工具调用 | 是 | `TOOL_REALLY_CALLED` 未通过 | `redteam/C-trace/forge_agent.sh` | DEFENDED |
| Judge 独立性 | 静态块抢先输出成功令牌 | 是 | command nonce 拦截，run FAILED | `redteam/D-command/game_agent.sh` | DEFENDED |
| Judge 独立性 | 篡改 `hidden/judge.rules.yaml` | 是 | `INTEGRITY_BROKEN` | `runs/redteam/code-fix-001/run_20260707_010214_eg83/report/report.json` | DEFENDED |
| 可复现性 | 同一 submission 判 3 次 | 是 | 同分同规则指纹 | `runs/redteam/f1.json` 至 `f3.json` | DEFENDED |
| 多轮提交 | attempt_001 失败，attempt_002 修复 | 是 | 最佳选择 `attempt_002` | `runs/audit-after-fix/api-payload-001/run_20260707_005707_nfm6/report/report.json` | DEFENDED |
| Report 可信度 | JSON/MD 报告、attempt、规则版本、trace 路径 | 是 | 均存在 | `runs/audit-baseline/.../report/report.json` | DEFENDED |

## 5. 成熟度评分

| 维度 | 分数 | 证据 | 扣分原因 |
|---|---:|---|---|
| TaskSpec | 5 | `task.yaml` 支持版本、类型、runtime、scoring、allowed_tools | 暂无硬扣分 |
| Work / Hidden 隔离 | 2 | `redteam/A-hidden/peek_agent.sh` 可读 hidden | 目录分离但无强隔离 |
| Submission Contract | 5 | JSON schema、分型 schema、大小限制、未知字段拒绝 | 自定义 schema 仍依赖任务作者 |
| Judge | 4 | 确定性 rules、script、版本、指纹、blocking | 无 LLM judge 注入防护实现 |
| Trace | 4 | JSONL、HMAC、trace 截断检测 | 仍不是 OS append-only |
| Tool Adapter | 4 | allowed_tools、mock、签名 trace、失败留痕 | 未支持真实工具权限/回放策略 |
| 多轮反馈 | 4 | feedback、best attempt、timeout、stop hook、resume | auto-eval 未实现 |
| Report | 3 | JSON + Markdown、失败统计、证据路径 | 无多任务/多 Agent 对比 |
| 安全 | 2 | canary、HMAC、hidden 指纹、allowed tools | CLI Agent 可读项目文件和 hidden |
| CI / 回归 | 4 | Maven tests、CLI、`--fail-on-not-passed`、redteam script | 无 CI 配置文件 |

总分：37 / 50。成熟度等级：可内部试用。

## 6. 已发现问题清单

### P0

问题：CLI Agent 可读取 `hidden/` 后照抄答案。
证据：`redteam/A-hidden/peek_agent.sh` 通过 `AEL_RUN_DIR/meta.json` 定位 `task_dir`，读取 `hidden/expected/answer.json`；最终 `PASSED` 且 `canary_leaks=0`。
影响：不可信 Agent 可绕过真实解题过程，评估结果失真。
复现方式：`bash redteam/run_all.sh`，查看 `A 偷看hidden(外科式)`。
修复建议：容器化 Runner，只挂载 workspace/inbox/feedback；hidden 和 task root 不挂载给 Agent；网络/文件权限默认拒绝。
验证方式：同一 redteam 用例应无法读取 hidden，run 应失败或产生明确安全事件。
优先级：P0。

### P1

问题：报告曾把无签名伪造 tool trace 统计为真实工具调用。
证据：修复前 forged trace report 出现 `total_calls=3`；修复后 `total_calls=0`、`untrusted_trace_events=3`。
影响：报告误导审计人员，虽然 judge 已拦截。
复现方式：`redteam/C-trace/forge_agent.sh`。
修复建议：已修复，report 按 trace signature 过滤。
验证方式：`bash redteam/run_all.sh` 中 `C2 report伪造trace统计` 为 DEFENDED。
优先级：P1，已修复。

问题：submission 曾接受 `passed=true`、`score=100` 等未知顶层字段。
证据：修复前 `bin/agent-eval judge --task tasks/api-payload-001 --submission redteam/B-submission/b7_extra_fields.json` 进入 judge；修复后 exit=3，报 `提交包含未授权顶层字段`。
影响：不会直接骗过 judge，但污染评估工件并削弱提交契约。
复现方式：运行上述 judge 命令。
修复建议：已修复，内置 schema 增加 Java 侧顶层字段白名单。
验证方式：`SubmissionManagerTest` 与 `redteam/run_all.sh`。
优先级：P1，已修复。

问题：trace 文件曾可被 Agent 删除，普通任务仍通过。
证据：`redteam/C-trace/delete_trace_agent.sh` 删除 trace 后提交合法答案；修复后 run 状态为 `ERROR`。
影响：审计链可被截断。
复现方式：`bash redteam/run_all.sh` 中 `C3 删除trace审计链`。
修复建议：已增加 run 收尾 trace 起点和 seq 连续性检查；长期仍需 OS 层 append-only 或隔离。
验证方式：`C3` 为 DEFENDED。
优先级：P1，已修复检测。

### P2

问题：没有多 Agent 横向对比报告。
证据：`ReportGenerator` 只处理单个 run 目录。
影响：做模型/Agent 选型时需要外部汇总。
修复建议：新增 task set / run set 聚合报告。
验证方式：多个 run 输出同一份 comparison JSON/Markdown。
优先级：P2。

问题：auto-eval 配置存在但未实现。
证据：`RunManager` 对 `auto_eval_interval_seconds` 仅 warn 并忽略。
影响：无法采样长任务过程状态。
修复建议：内部试用前可暂不做；真实长任务评估再实现。
验证方式：配置 interval 后 trace 出现 auto-eval 事件。
优先级：P2。

## 7. 已实现修复

| 修复项 | 修改文件 | 验证命令 | 验证结果 |
|---|---|---|---|
| Report 只统计签名可信 tool trace，并记录未可信事件 | `src/main/java/com/agenteval/report/ReportGenerator.java`、`src/main/java/com/agenteval/runner/RunManager.java`、`src/test/java/com/agenteval/report/ReportGeneratorTest.java` | `mvn -q -Dtest=ReportGeneratorTest test`；`bash redteam/run_all.sh` | 通过，`C2` DEFENDED |
| 内置 submission 拒绝未知顶层字段 | `src/main/java/com/agenteval/submission/SubmissionManager.java`、`src/test/java/com/agenteval/submission/SubmissionManagerTest.java` | `mvn -q -Dtest=SubmissionManagerTest test`；`bin/agent-eval judge ... b7_extra_fields.json` | exit=3，明确报错 |
| Runner 检测 trace 截断/删除 | `src/main/java/com/agenteval/runner/RunManager.java`、`redteam/C-trace/delete_trace_agent.sh`、`redteam/run_all.sh` | `mvn -q test`；`bash redteam/run_all.sh` | `C3` 由 VULNERABLE 变为 DEFENDED |

## 8. 剩余缺口

未修 P0 hidden 读取：这不是小补丁能可靠解决的问题。只删除 `meta.json.task_dir` 或隐藏路径不能阻止同机 Agent 用 shell 搜索仓库、遍历父目录或读取其他文件。必须用容器、沙箱、不同用户权限或等价隔离。

未修多 Agent 对比、auto-eval、真实工具权限：这些属于产品成熟度增强，不影响当前 Demo/MVP 的核心闭环。

## 9. 下一步最小行动计划

1. 增加 Docker Runner：Agent 容器只挂载 `workspace/`、`inbox/`、`feedback/`，不挂载 `tasks/` 和 `hidden/`。
2. 把 `redteam/run_all.sh` 接入 CI，任何 VULNERABLE 项或命令失败都阻断。
3. 新增 `redteam-hidden-symlink`：验证 symlink、相对路径、`find` 搜索 hidden 都被容器隔离挡住。
4. 新增 report comparison 命令：汇总多个 run 的 score、passed、tool_usage、failure_stats。
5. 为 trace 增加链式哈希或 manifest，报告中显示 trace 完整性状态。
6. 实现 HTTP AgentAdapter 前，先保留 CLI wrapper 路径，避免过早扩展协议面。

## 10. 结论

1. 能作为 Demo：能。
2. 能作为 MVP：能，前提是用于可信/半可信内部评测。
3. 能给真实 Agent 做内部评估：可以，但不能把安全隔离结论当真。
4. 能接真实业务流程：不建议，缺少强隔离和真实工具权限治理。
5. 距离成熟框架还差：容器级隔离、OS 级文件/网络权限控制、CI 红队门禁、多 Agent 对比、真实工具治理和更强 trace 防篡改。

## 11. 复审更新（2026-07-07，含 Docker Runner 落地）

本节记录快照之后的演进与复核结果，并逐项修正正文中已过时的结论；正文其余部分保留为历史证据。

### 11.1 当前实测状态（全部复跑验证）

| 命令 | 结果 |
|---|---|
| `mvn -q -B clean verify` | 通过：159 个测试全绿（含 docker 沙箱端到端用例，无 Docker 环境自动跳过），JaCoCo 指令覆盖率 ≥ 0.75 门禁，Checkstyle 无违规 |
| `bin/agent-eval suite --tasks-root tasks --fail-on-not-passed` | 通过：5/5 任务稳定通过 |
| `bash redteam/run_all.sh`（Docker 就绪） | 19 项全 DEFENDED / VULNERABLE=0（登记基线 0）/ INFRA=0 / CHECK=0 |
| `bash redteam/run_all.sh`（无 Docker 回退） | 17 项：16 DEFENDED / 1 VULNERABLE（登记基线 1：红队 A 外科式偷看）/ INFRA=0 / CHECK=0 |
| `bash redteam/test_gate.sh` | 门禁判定 fail-closed 契约自测 7/7 通过 |
| `RT_ALLOWED_VULN=0 bash redteam/run_all.sh` | 负向验证：无 Docker 时退出码 1，门禁按预期拦截基线外 VULNERABLE |
| `bash bin/ci-smoke.sh` | 四道门禁全部通过 |

### 11.2 快照后新增的防护（均已 DEFENDED）

- **G 越权工具调用**：网关按任务 allowlist 拒绝并留痕 `tool_not_allowed`，`tool_call_required` 一票否决。
- **H 工具入参夹带 canary 外泄**：canary 扫描面扩到 `traces/`，经网关入参外传被检出。
- **D2 PASS_TO_PASS 回归**（借鉴 SWE-bench）：修好目标缺陷却改坏既有 `maxPrice` 行为，被隐藏行为规格一票否决。
- **I 过程对终态错**（借鉴 tau-bench）：`world_state` check 把签名可信且成功的写工具调用折叠为世界终态与期望比对，「流程对、提交对、终态错」被一票否决。
- **门禁 fail-closed 化**：`VULNERABLE` 超出登记基线 `RT_ALLOWED_VULN`（默认 1）、或出现 INFRA / CHECK 即失败；每次运行产出结构化工件 `runs/redteam/redteam_report.json`；判定逻辑抽为 `redteam/gate_lib.sh` 纯函数并配 `redteam/test_gate.sh` 正/负向自测，已接入 `.github/workflows/ci.yml` 与 `bin/ci-smoke.sh`。
- **J 组 llm_rubric 框架护栏**：非确定性 LLM 判分的滥用面在框架侧——J1 有效权重 >30% 上限被 `validate` 深度 lint 拒绝、J2 标记 `blocking` 被拒（非确定性信号不得一票否决）、J3 判分模型未配置时 `judge` fail-closed（非零退出且绝不产出分数）。三项均确定性、无需真实模型、全程断网；另有 `LlmRubricRedTeamTest` 用 mock 端点覆盖「注入降级为数据 / 越界分数钳制 / 违约 fail-closed」。

### 11.3 正文时效性修正

| 正文位置 | 快照结论 | 当前状态 |
|---|---|---|
| §1 / §2 | 红队矩阵 9 DEFENDED / 1 VULNERABLE | Docker 就绪 19 项全 DEFENDED（基线 0）；无 Docker 回退 17 项 16 DEFENDED / 1 VULNERABLE（基线 1） |
| §3 未实现清单 | 多 Agent 对比报告未实现 | 已实现：`suite --agents-file` 任务×Agent 矩阵 + pass^k 可靠性 + 排名面板 |
| §5 CI/回归 扣分项 | 无 CI 配置文件 | `.github/workflows/ci.yml` 与 `bin/ci-smoke.sh` 已落地（测试/体检/套件/红队四步） |
| §6 P2 | 没有多 Agent 横向对比报告 | 已完成（同上） |
| §9 行动计划 | 第 2 条（红队接入 CI）、第 4 条（对比报告）待做 | 均已完成；第 5 条 trace 完整性已部分覆盖（截断/删除检测） |

### 11.4 维持不变的结论

- §3 未实现清单中的真实工具权限模型此后已按 http 后端白名单落地（Phase 3）；auto-eval 后台采样、`llm_rubric` LLM 判分及其框架侧防线（权重封顶 / 禁 blocking / fail-closed / 注入降级为数据）均已落地并有 J 组红队 + `LlmRubricRedTeamTest` 守护。HTTP AgentAdapter 已按窄口径落地（2026-07-07：框架按轮 POST、响应体即提交，`run`/`suite`/`agents-file` 全线支持，端到端测试覆盖 fail→pass/放弃/5xx/不可达路径）——注意其安全边界与 cli 适配器相同（同机信任模型）。
- 默认（非容器）模式下 command nonce 非密码学强度、run 结束后产物篡改两处残余风险声明继续有效（见 README「安全边界」）；其中 nonce 重放已被 §11.5 的 Docker Runner 在容器模式下根治。

### 11.5 P0 闭环：Docker Runner 落地（2026-07-07）

§9 第 1 条与 §6 P0（CLI Agent 同机可读 `hidden/`）已实现根因修复：新增 `--sandbox docker`（仅 `cli` 模式；`run`/`suite`/`agents.yaml` 全线支持），把 Agent 命令包进 `docker run --rm`，容器**只**挂载 `workspace/`(rw)、`inbox/`(rw)、`feedback/`(ro)、`instructions.md`(ro)，`hidden/`/`judge/`/`traces/`/任务目录/宿主家目录一律不挂载，默认 `--network none` 断网。

- **§9 第 1 条（容器只挂 Agent 可触碰区）**：已实现，见 `DockerSandbox` / `DockerAgentAdapter`。
- **§9 第 3 条（symlink / 相对路径 / find 逃逸）**：已新增红队用例 `A-sym`（workspace 内 symlink 指向宿主 hidden）、`A-find`（全盘 find 评审材料），Docker 就绪时均 DEFENDED。
- **红队矩阵变化**：Docker 就绪时 A 由 VULNERABLE 翻转为 DEFENDED，另新增 A-sym / A-find 两项 DEFENDED，叠加 J 组 llm_rubric 框架护栏 3 项 DEFENDED，**19 项全 DEFENDED，登记基线 `RT_ALLOWED_VULN` 由 1 降为 0**（基线随 Docker 就绪度自适应：不就绪则回退非容器 A 并保留基线 1）。
- **成熟度评分修正**：§5「Work / Hidden 隔离」原 2 分（目录分离但无强隔离）——启用 Docker Runner 后升至 4（容器只挂 Agent 可触碰区，红队多角度逃逸均被挡；扣分保留于「非容器默认模式仍无强隔离」与「容器强度依赖 Docker 配置」）。
- **残余**：real-tool 治理、结果仓库/看板仍待做；容器隔离强度依赖 Docker 配置本身（默认 root-in-container、共享内核），镜像可信由评估方保证。
