# AgentEval-Lite

轻量级、通用的 AI Agent 评估框架——参考 [EdgeBench](https://github.com/ByteDance-Seed/EdgeBench)/SForge 的核心思想（Work/Judge 隔离、结构化提交契约、隐藏判分、多轮受控反馈、全过程留痕、可复现评分），做成**零外部依赖、单 jar、目录即隔离**的本地落地版。

不限定业务场景：代码修复、API payload 生成、文档分析、工具调用纪律、需求评审等任意可结构化验收的 Agent 任务都能装进同一套 TaskSpec + Judge 模型。

## 核心思想（60 秒版）

| 概念 | 落地 |
| --- | --- |
| Work / Judge 隔离 | Agent 只见 `workspace/`（work/ 的私有副本）与 `instructions.md`；评审规则、期望数据、mock 应答库全在任务的 `hidden/`，靠指纹完整性校验 + canary 泄露探针 + 越界即判负来守卫 |
| Submit Contract | 唯一有效提交通道是把 JSON 写进 `inbox/attempt_NNN.json`，信封 + 分型双重 JSON Schema 校验，自然语言输出不计分 |
| Hidden Judge | 声明式规则引擎（14 种确定性 check）+ 可选外部脚本；`command` 型 check 在 workspace 临时副本上执行（ephemeral，评审不污染现场） |
| Feedback Loop | 每轮产出受控反馈（粒度可配：summary / failed_rules / full），`private_notes` 与 expected 值永不回传；Agent 拿反馈修正后再提交 |
| Stop Hook（轻量） | Agent「自称完成」不算完成——未过线且有剩余轮次就带反馈继续，事件留痕 `stop_hook_triggered` |
| Auto-Resume（轻量） | 每轮原子落盘 `run_state.json`，进程被杀后 `--resume` 续跑；恢复前校验 hidden 指纹，被篡改则 `INTEGRITY_BROKEN` 熔断 |
| Trace Log | append-only JSONL，16 类事件（生命周期/工具/提交/评分/反馈/恢复/错误）；工具网关为**框架进程内常驻服务**，事件用每 run 私密密钥 HMAC 签名，判分只认可核验的事件（Agent 无法伪造工具调用）；`export` 一键导出 OTLP/OpenInference 供 Phoenix / Langfuse 看板摄取 |
| 可复现评分 | 评分结果携带 engine 版本 + hidden 目录指纹 + 提交指纹 + workspace 指纹；`agent-eval judge` 可随时离线复算，同输入必同分 |

## 快速开始

```bash
# 构建（Java 17+，Maven）
mvn -q package                 # 含 112 个单元/端到端测试
# 产物: target/agent-eval-lite-0.1.0-cli.jar（bin/agent-eval 会自动定位）

# 看任务库
bin/agent-eval list

# 一键冒烟门禁（单元测试 + 任务集回放 + 红队回归三道闸，本地/CI 同口径）
bash bin/ci-smoke.sh

# 跑一个内置演示（脚本回放的"Agent"：第 1 轮算错金额被打回，第 2 轮按反馈修正后通过）
bin/agent-eval run --task tasks/api-payload-001 --agent scripted \
    --script tasks/api-payload-001/samples/replay.yaml

# 看报告
cat runs/api-payload-001/run_*/report/report.md
```

### 三种 Agent 接入方式

```bash
# 1. manual：把"人"当被评 Agent（调任务、建人工基线）
bin/agent-eval run --task tasks/prd-review-001 --agent manual --submission my.json   # 单发
bin/agent-eval run --task tasks/prd-review-001 --agent manual                        # 交互（按提示把提交写进 inbox）

# 2. scripted：确定性回放（CI 回归、任务自测）——见各任务 samples/replay.yaml
bin/agent-eval run --task tasks/code-fix-001 --agent scripted \
    --script tasks/code-fix-001/samples/replay.yaml

# 3. cli：驱动任意命令行 Agent（claude / codex / cursor-agent / 自研）
bin/agent-eval run --task tasks/code-fix-001 --agent cli \
    --cmd 'claude -p "$(cat {instructions})" --dangerously-skip-permissions' \
    --model claude-sonnet
```

`cli` 模式的命令模板支持占位符 `{instructions} {workspace} {inbox} {attempt_id} {feedback} {run_dir}`（自动 shell 转义），并注入环境变量 `AEL_RUN_DIR / AEL_INSTRUCTIONS / AEL_WORKSPACE / AEL_INBOX / AEL_ATTEMPT_ID / AEL_FEEDBACK`。Agent 进程 cwd 固定为 workspace，超时强杀。

### 其余命令

```bash
bin/agent-eval validate --task tasks/xxx          # 任务静态体检（结构+引用+规则文件）
bin/agent-eval suite --tasks-root tasks --fail-on-not-passed   # 任务集回放批跑（CI 冒烟门禁）
bin/agent-eval suite --agent cli --cmd '...' --label my-agent --repeat 3   # 真实 Agent 批量过全部任务 + pass^3 可靠性
bin/agent-eval suite --agents-file agents.yaml --repeat 2      # 多 Agent 并列对比（任务×Agent 矩阵 + 排名面板）
bin/agent-eval judge --task tasks/xxx --submission sub.json   # 离线复算分数（可复现承诺）
bin/agent-eval report --run runs/xxx/run_yyy      # 从工件重建报告（纯读幂等）
bin/agent-eval run --resume runs/xxx/run_yyy --agent cli --cmd '...'   # 断点续跑
bin/agent-eval tool call user.lookup --input '{"user_id":"u_1001"}'    # Agent 侧工具调用（需 AEL_RUN_DIR）
bin/agent-eval export --run runs/xxx/run_yyy      # trace 导出 OTLP/OpenInference（--endpoint 可直推 Phoenix 等看板）
```

退出码：`0` 评估正常完成（无论通过与否）；`1` 参数/输入错误；`2` 框架故障或完整性熔断；`3` 配了 `--fail-on-not-passed` 且未通过（CI 门禁用）。`suite` 子命令共享同一套退出码：全部任务稳定通过为 `0`，存在未通过项且配了 `--fail-on-not-passed` 为 `3`。

### suite：批跑、pass^k 可靠性与多 Agent 对比

`suite` 既是 CI 冒烟门禁，也是生产 Agent 的度量/选型入口：

- **CI 冒烟**（默认 `--agent scripted`）：批跑全部任务的确定性回放，验证框架闭环未劣化；
- **真实 Agent 度量**：`--agent cli --cmd '...'` 让真实命令行 Agent 批量过全部任务；`--repeat k` 把每个任务跑 k 次，按 **pass^k** 口径（k 次全过才算稳定通过，借鉴 tau-bench）判可靠性，抓出「首跑过、复跑挂」的不稳定 Agent；
- **多 Agent 并列对比**：`--agents-file agents.yaml` 让多个 Agent（scripted/cli 混搭）跑同一任务集，产出「任务 × Agent」矩阵与按稳定通过数排序的排名面板；
- 报告（`suite_report.json` / `suite_report.md`）逐 run 记录真实墙钟时延并聚合平均耗时，作为选型时的成本参考（token 成本采集需 Agent 侧上报，暂未内置）。

`agents.yaml` 格式：

```yaml
agents:
  - label: baseline-scripted
    type: scripted            # 用各任务 samples/replay.yaml 回放
  - label: my-agent
    type: cli
    cmd: 'claude -p "$(cat {instructions})" --dangerously-skip-permissions'
```

### CI 冒烟门禁

`.github/workflows/ci.yml` 与 `bin/ci-smoke.sh` 同口径三道闸：`mvn clean verify`（112 个测试 + Checkstyle 静态检查 + JaCoCo 指令覆盖率 ≥ 0.75 下限）→ 任务集回放批跑（`suite --fail-on-not-passed`，任一任务闭环回归被破坏即失败）→ 红队回归（`redteam/run_all.sh`，fail-closed：`VULNERABLE` 超出登记基线 `RT_ALLOWED_VULN`（默认 1）、或出现 INFRA（报告缺失/解析失败）/ CHECK（观测值偏离登记预期）即失败，另产出结构化工件 `redteam_report.json`）。全部步骤确定性（回放/mock，不依赖模型或网络）。

## 内置示例任务（5 个，覆盖 5 类任务形态）

| 任务 | 类型 | 评的是什么 | 判分手段 |
| --- | --- | --- | --- |
| `code-fix-001` | code_fix | 修复 off-by-one + null 缺陷；虚报修改会被基线指纹识破 | 隐藏行为规格程序编译执行（command）+ changed_files 核验 |
| `api-payload-001` | api_payload | 读接口文档生成订单 payload，VIP 折扣算对 | 隐藏 schema + 期望值比对（expected_from） |
| `doc-analysis-001` | document | 三份 release notes 里找全 5 处破坏性变更 | 关键点覆盖率（部分得分）+ 来源引用校验 |
| `tool-call-001` | tool_call | 必须真调 `user.lookup` 查等级再开卡；编造 call_id 判负 | trace 工具轨迹核验（blocking）+ canary 泄露探针 |
| `prd-review-001` | review | 评审 PRD：过期/并发/补偿/风控四类缺口 + 验收标准 | 风险点覆盖率（部分得分）+ 结构检查 |

每个任务的 `samples/replay.yaml` 都编排了「第 1 轮失败 → 按反馈修正 → 第 2 轮通过」的完整闭环，可直接跑通观察多轮反馈机制。

## 任务目录契约

```text
tasks/<task-id>/
├── task.yaml            # 任务规格（对 Agent 可见的部分会渲染进 instructions）
├── work/                # Agent 可见材料（run 时复制为私有 workspace）
├── hidden/              # Agent 永不可见：judge.rules.yaml / expected/ / tools/ / 评分脚本
└── samples/             # 示例提交 + replay.yaml（任务自测）

runs/<task-id>/<run-id>/ # 每次评估的自包含产物
├── meta.json  run_state.json  instructions.md
├── workspace/  inbox/  feedback/          # Agent 可触碰区
└── judge/  traces/  report/  agent-logs/  # 评审侧禁区
```

### 写一个新任务

1. 建目录 `tasks/my-task-001/`，写 `task.yaml`（`task_id` 必须等于目录名；维度权重之和必须等于满分）；
2. 放 `work/` 材料，写 `hidden/judge.rules.yaml`（14 种 check 见下）；期望值放 `hidden/expected/`，用 `expected_from: "expected/x.json#/指针"` 引用，**不要**写进对外文案；
3. `bin/agent-eval validate --task tasks/my-task-001` 过体检；
4. 写 `samples/attempt-pass.json` + `replay.yaml`，用 scripted 跑通 fail→pass 闭环。

**check 类型**：`json_schema`、`jsonpath_equals`（支持 `expected`/`expected_from`/`tolerance`）、`jsonpath_exists`、`jsonpath_matches`、`list_coverage`（关键点组覆盖率，部分得分）、`evidence_sources_valid`、`workspace_file_exists`、`workspace_file_contains`、`changed_files_verified`（基线指纹核验）、`command`（临时副本上执行，`{hidden} {workspace} {submission} {task}` 占位符）、`tool_call_required`（trace 核验 + call_id 引用核验）、`tool_call_forbidden`、`world_state`（把 trace 中可信且成功的写工具调用折叠为世界终态，与期望的 `{tool, input}` 列表比对；`scope: attempt/run`、`order_sensitive` 可选——评的是「实际改成了什么」而非「提交里说了什么」）、`no_canary_leak`。

每个 check 可配 `dimension / points / blocking（一票否决）/ severity / feedback_fail（对外文案，禁含期望值）`。计分：维度得分 = 权重 × Σearned/Σpossible；通过 = 总分 ≥ 通过线 **且** 无 blocking 失败。

## 安全边界（可信硬化后的诚实声明）

本地模式下 Agent 与框架同机、共享文件系统。在**不引入容器**的前提下，我们把最容易被主动作弊利用的路径做了进程内硬化。红队回归见 `redteam/run_all.sh`（当前 14 项，13 DEFENDED / 1 登记基线残留），并已并入 CI 门禁（fail-closed）：`VULNERABLE` 超出登记基线、或出现 INFRA / CHECK 即失败；每次运行产出 `runs/redteam/redteam_report.json` 供 CI 工件与趋势分析。

### 已修复（附红队证据）

1. **trace 不可伪造（P0-2，根因修复）**：工具网关从「Agent 派生的子进程」改为「框架进程内常驻服务」（`ToolGatewayServer`，监听 loopback 临时端口）。每条框架事件用**每 run 私密密钥**做 HMAC-SHA256 签名（`TraceSigner`），密钥仅存于框架内存、Agent 执行期间不落盘（`TraceSecret` 的安全不变量）。判分只统计签名可核验的 `tool_call` 事件——Agent 直接往 `trace.jsonl` 手写的伪造事件（无合法签名）被静默丢弃。→ **红队 C 由 VULNERABLE 翻转为 DEFENDED**。
2. **`command` 短路被挡（P0-3）**：judge 每轮生成随机 nonce，注入命令模板 `{nonce}` 与 `output_regex`；受信任的隐藏 harness 只在跑完真实断言后回显含 nonce 的成功标记。事先写死的成功令牌（如静态块抢先 `System.out.println("ALL_CHECKS_PASSED"); System.exit(0)`）因不含本轮随机 nonce 而无法命中。→ **红队 D 翻转为 DEFENDED**。
3. **偷看 hidden 更易被抓（P0-1 缓解）**：`no_canary_leak` 扫描面从 `workspace/` 扩到 `agent-logs/`、`inbox/` 与 `traces/`——Agent 把 hidden 内容 dump 到 stdout（最常见的越界形态，CLI 适配器会落盘到 agent-logs）会被 canary 命中。→ **红队 A2（dump 含 canary 的应答库）为 DEFENDED**。
4. **借工具入参外泄被封（P0-1 扩展）**：Agent 若把偷到的 canary 当作工具入参经网关外泄（网关会把 input 原样记入 append-only trace），仅扫 workspace/日志会漏检——canary 扫描已覆盖 `traces/`，据此检出 trace 中的 canary。→ **新增红队 H（工具入参夹带 canary）为 DEFENDED**。
5. **越权工具调用被拒**：网关按任务 `allowed_tools` 白名单拦截未授权工具（`error=tool_not_allowed` 留痕），不产生成功调用记录，`tool_call_required` 一票否决。→ **新增红队 G（调 allowlist 外的工具）为 DEFENDED**。
6. **PASS_TO_PASS 回归门（借鉴 SWE-bench）**：`code-fix-001` 的隐藏行为规格分 FAIL_TO_PASS（缺陷修复）与 PASS_TO_PASS（既有 `maxPrice` 行为不得改坏）两组断言，挡住「为过缺陷用例而把无关既有功能改坏/删除」的回归式作弊。→ **新增红队 D2（修好 total 却改坏 maxPrice）为 DEFENDED**。

### 仍存的残余风险（诚实告知，需 Phase 4 容器隔离根治）

- **外科式偷看不可检测**：Agent 若只 `sed` 出隐藏答案里的目标数值、既不整段转存也不触碰 canary，则无痕（红队 A 仍为 VULNERABLE）。canary 只能抓「抄了带 token 的内容」，抓不住「读了可推导的答案值」。同机同用户下无法阻止文件读取，彻底隔离需容器只挂载 workspace。
- **command nonce 非密码学强度**：nonce 经命令行参数传给 harness，理论上 Agent 的进程内代码可在运行时读自身命令行并回显 nonce 来短路。它挡住了「事先写死令牌」这一常见作弊，但挡不住「运行时动态读取并重放 nonce」的高级攻击——后者同样需进程/容器隔离才能根治。
- **run 结束后的产物篡改**：签名密钥在 run 收尾（Agent 已停止）时才落盘以供离线复核与 resume；对已完成 run 的离线产物篡改属另一层威胁，靠目录指纹 + 从干净任务目录复算发现。

**结论**：可信度已从「仅适合协作型/非对抗 Agent」提升到「能挡住常见作弊手法」，可对接**受控的真实业务评估**（内部 Agent 能力度量、CI 门禁、多轮改进回归）。作为面向公开/强对抗环境的作弊裁决器，仍需 Phase 4 硬隔离（Docker 只挂载 workspace）补齐「外科式偷看」与「运行时 nonce 重放」两处残余。

## 文档

- `docs/01-代码库理解报告.md` — agentScopeScaffold 分析与选址决策（为何独立成项目）
- `docs/02-EdgeBench-SForge-调研总结.md` — 调研结论与取舍清单
- `docs/03-AgentEval-Lite-设计方案.md` — 完整设计（架构图、Schema、时序、安全模型、示例任务、实施计划）
- `docs/04-成熟评估框架调研与ROI报告.md` — 成熟评估框架横向调研与借鉴取舍

## 路线图

- **Phase 1（当前）**：单机 CLI 闭环——TaskSpec / 三适配器 / 规则+脚本 Judge / trace / 报告 / resume / 5 示例任务
- **Phase 1.5（可信硬化，已落地）**：工具网关常驻服务化 + trace HMAC 签名（P0-2）、`command` nonce 防短路（P0-3）、canary 扫描扩面到 agent-logs/inbox/traces（P0-1，含借工具入参外泄）、越权工具调用拦截、PASS_TO_PASS 回归门——见「安全边界」与 `redteam/run_all.sh`
- **Phase 1.6（工程化，已落地）**：任务集回放批跑（`agent-eval suite`）+ CI 冒烟门禁（`bin/ci-smoke.sh`、`.github/workflows/ci.yml`：测试 + 套件 + 红队三道闸）
- **Phase 1.7（生产度量，已落地）**：`suite` 支持真实 cli Agent 批量过任务、`--repeat k` 的 pass^k 可靠性口径、`--agents-file` 多 Agent 并列对比（任务×Agent 矩阵 + 排名面板）、逐 run 时延采集与聚合
- **Phase 1.8（可观测与质量门禁，已落地）**：`agent-eval export` 导出 OTLP/OpenInference（Phoenix / Langfuse / OTel Collector 直接摄取）、`world_state` 终态比对 check、红队门禁 fail-closed 化 + `redteam_report.json` 结构化工件、JaCoCo 覆盖率下限 + Checkstyle 进 CI
- **Phase 2**：HTTP AgentAdapter（评 agentScopeScaffold 的 chat API）、token/cost 采集（需 Agent 侧上报）
- **Phase 3**：真实工具（http/db 白名单 + 响应存档）、auto-eval 周期采样、LLM judge（低权重维度）
- **Phase 4**：Docker Runner（work 容器不挂载 hidden，根治外科式偷看与运行时 nonce 重放）、结果仓库与看板
