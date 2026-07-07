# AgentEval-Lite

轻量级、通用的 AI Agent 评估框架——参考 [EdgeBench](https://github.com/ByteDance-Seed/EdgeBench)/SForge 的核心思想（Work/Judge 隔离、结构化提交契约、隐藏判分、多轮受控反馈、全过程留痕、可复现评分），做成**零外部依赖、单 jar、目录即隔离**的本地落地版。

不限定业务场景：代码修复、API payload 生成、文档分析、工具调用纪律、需求评审等任意可结构化验收的 Agent 任务都能装进同一套 TaskSpec + Judge 模型。

## 核心思想（60 秒版）

| 概念 | 落地 |
| --- | --- |
| Work / Judge 隔离 | Agent 只见 `workspace/`（work/ 的私有副本）与 `instructions.md`；评审规则、期望数据、mock 应答库全在任务的 `hidden/`，靠指纹完整性校验 + canary 泄露探针 + 越界即判负来守卫 |
| Submit Contract | 唯一有效提交通道是把 JSON 写进 `inbox/attempt_NNN.json`，信封 + 分型双重 JSON Schema 校验，自然语言输出不计分 |
| Hidden Judge | 声明式规则引擎（15 种 check：14 种确定性 + 1 种低权重 `llm_rubric`）+ 可选外部脚本；`command` 型 check 在 workspace 临时副本上执行（ephemeral，评审不污染现场） |
| Feedback Loop | 每轮产出受控反馈（粒度可配：summary / failed_rules / full），`private_notes` 与 expected 值永不回传；Agent 拿反馈修正后再提交 |
| Stop Hook（轻量） | Agent「自称完成」不算完成——未过线且有剩余轮次就带反馈继续，事件留痕 `stop_hook_triggered` |
| Auto-Resume（轻量） | 每轮原子落盘 `run_state.json`，进程被杀后 `--resume` 续跑；恢复前校验 hidden 指纹，被篡改则 `INTEGRITY_BROKEN` 熔断 |
| Trace Log | append-only JSONL，17 类事件（生命周期/工具/提交/评分/反馈/恢复/错误）；工具网关为**框架进程内常驻服务**，事件用每 run 私密密钥 HMAC 签名，判分只认可核验的事件（Agent 无法伪造工具调用）；`export` 一键导出 OTLP/OpenInference 供 Phoenix / Langfuse 看板摄取 |
| 可复现评分 | 评分结果携带 engine 版本 + hidden 目录指纹 + 提交指纹 + workspace 指纹；`agent-eval judge` 可随时离线复算，同输入必同分 |

## 快速开始

```bash
# 构建（Java 17+，Maven）
mvn -q package                 # 含 159 个单元/端到端测试（docker 沙箱端到端用例在无 Docker 环境自动跳过）
# 产物: target/agent-eval-lite-0.1.0-cli.jar（bin/agent-eval 会自动定位）

# 看任务库
bin/agent-eval list

# 一键冒烟门禁（CODEMAP 漂移 + 单元测试 + 任务集回放 + 红队回归四道闸，本地/CI 同口径）
bash bin/ci-smoke.sh

# 跑一个内置演示（脚本回放的"Agent"：第 1 轮算错金额被打回，第 2 轮按反馈修正后通过）
bin/agent-eval run --task tasks/api-payload-001 --agent scripted \
    --script tasks/api-payload-001/samples/replay.yaml

# 看报告
cat runs/api-payload-001/run_*/report/report.md
```

### 四种 Agent 接入方式

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

# 4. http：评估服务形态的 Agent（chat API / Agent 平台入口 / 自研服务）
bin/agent-eval run --task tasks/api-payload-001 --agent http \
    --endpoint http://localhost:8080/agent --http-header 'Authorization: Bearer xxx'

# cli + Docker 沙箱：对不可信/强对抗 Agent 做容器强隔离（只挂 workspace/inbox/feedback/instructions，
# hidden 根本不在容器里 → 外科式偷看无从谈起；默认 --network none 断网）
bin/agent-eval run --task tasks/code-fix-001 --agent cli --sandbox docker \
    --sandbox-image my-agent-image:latest \
    --cmd 'claude -p "$(cat {instructions})" --dangerously-skip-permissions'
```

`cli` 模式的命令模板支持占位符 `{instructions} {workspace} {inbox} {attempt_id} {feedback} {run_dir}`（自动 shell 转义），并注入环境变量 `AEL_RUN_DIR / AEL_INSTRUCTIONS / AEL_WORKSPACE / AEL_INBOX / AEL_ATTEMPT_ID / AEL_FEEDBACK`。Agent 进程 cwd 固定为 workspace，超时强杀。

`--sandbox docker`（仅 `cli` 模式，`run` / `suite` / `agents.yaml` 全线支持）把这条命令包进 `docker run --rm`，容器**只**挂载 `workspace/`(rw)、`inbox/`(rw)、`feedback/`(ro)、`instructions.md`(ro) 四个 Agent 可触碰点——`hidden/`、`judge/`、`traces/`、任务目录、宿主家目录都不在容器文件系统内，占位符替换为容器内路径（`/ael/workspace` 等），`{run_dir}` 因含评审禁区而置空。相关参数：`--sandbox-image <镜像>`（必填，须自带 Agent 命令所需环境）、`--sandbox-network`（默认 `none` 断网，需联网的 Agent 用 `bridge`）、`--sandbox-tool-jar`（挂入容器供 `agent-eval tool call` 用的 CLI jar，默认自动探测当前运行 jar；镜像需自带 JRE）、`--sandbox-docker-arg`（透传 `docker run`，如 `--memory=512m`，可重复）。非断网时容器经 `host.docker.internal` 回连宿主 loopback 上的常驻工具网关（token 仍作纵深防御）。docker 未安装或 daemon 未启动时以退出码 1 前移拦截，不误记为 Agent 低分。

`http` 模式走窄口径契约（框架推任务、收提交，不含 SSE/浏览器等重协议面）：框架每轮向 `--endpoint` `POST` 一个 JSON（`protocol=ael-http-agent/1`，含 `task_id / attempt_id / attempt_number / max_attempts / instructions 全文 / feedback（首轮 null）/ workspace_dir / inbox_dir / run_dir / tool_gateway{endpoint,token}`）；服务用 `200` + 提交信封 JSON 应答（响应体即本轮提交，之后的 schema 校验/判分/反馈与其他适配器同链路），`204` 表示放弃后续轮次，其他状态码/超时按本轮无提交继续。服务不可达按评估基础设施故障处理，不会误记为 Agent 低分。

### 其余命令

```bash
bin/agent-eval task init --id my-task-001         # 生成新任务脚手架（开箱即过 validate 与 fail→pass 回放闭环）
bin/agent-eval validate --task tasks/xxx          # 任务静态体检（结构+引用+规则文件+深度 lint：expected_from 断链/schema_file 缺失/白名单外工具前移拦截）
bin/agent-eval suite --tasks-root tasks --fail-on-not-passed   # 任务集回放批跑（CI 冒烟门禁）
bin/agent-eval suite --agent cli --cmd '...' --label my-agent --repeat 3   # 真实 Agent 批量过全部任务 + pass^3 可靠性
bin/agent-eval suite --agent http --endpoint http://localhost:8080/agent --label my-service   # 服务型 Agent 批量过全部任务
bin/agent-eval suite --agents-file agents.yaml --repeat 2      # 多 Agent 并列对比（任务×Agent 矩阵 + 排名面板）
bin/agent-eval suite --tier smoke --fail-on-not-passed         # 只批跑某分层（smoke/regression/security/domain，与 --tasks 取交集）
bin/agent-eval history --runs-root runs                        # 汇总历次 report.json → 跨 run/Agent/版本趋势（JSON+MD，只读离线）
bin/agent-eval judge --task tasks/xxx --submission sub.json   # 离线复算分数（可复现承诺）
bin/agent-eval report --run runs/xxx/run_yyy      # 从工件重建报告（纯读幂等）
bin/agent-eval run --resume runs/xxx/run_yyy --agent cli --cmd '...'   # 断点续跑
bin/agent-eval tool call user.lookup --input '{"user_id":"u_1001"}'    # Agent 侧工具调用（需 AEL_RUN_DIR）
bin/agent-eval export --run runs/xxx/run_yyy      # trace 导出 OTLP/OpenInference（--endpoint 可直推 OTLP/JSON 收集器）
```

`export` 产出标准 OTLP/JSON（OpenInference 语义：run→AGENT / attempt→CHAIN / tool_call→TOOL 三层 span），trace/span id 由 run/attempt/call id 确定性派生（幂等重推不分叉）。接受 OTLP/JSON 的收集器（OTel Collector 4318 端口、Langfuse 等）可 `--endpoint` 直推；只收 protobuf 的后端（如 Arize Phoenix 17.x）用 `research/poc/phoenix/push_otlp_json.py` 转 protobuf 推送（已实测 Phoenix 完整收到三层 span 树）。

退出码：`0` 评估正常完成（无论通过与否）；`1` 参数/输入错误；`2` 框架故障或完整性熔断；`3` 配了 `--fail-on-not-passed` 且未通过（CI 门禁用）。`suite` 子命令共享同一套退出码：全部任务稳定通过为 `0`，存在未通过项且配了 `--fail-on-not-passed` 为 `3`。

### 真实工具：http 后端白名单 + 响应存档（录制 → 回放）

工具默认走 `hidden/tools/<name>.responses.yaml` 应答库（确定性、零外呼）。需要连真实系统时，在 `task.yaml` 给工具声明 http 后端：

```yaml
allowed_tools:
  - name: weather.lookup
    description: 查询城市天气
    backend:
      type: http                       # 目前仅 http（db 只读连接需引入 JDBC 依赖，刻意不做，见 docs/04）
      url: https://api.example.com/weather   # 唯一可达目标（白名单即声明本身）
      method: POST                     # POST=入参作 JSON 请求体；GET=入参顶层标量作查询参数
      headers:
        Authorization: 'Bearer ${ENV:WEATHER_API_KEY}'   # 凭证从框架进程环境解析，永不落任务文件
      timeout_seconds: 10
```

运行模式由环境变量 `AEL_TOOL_MODE` 控制（契约归任务，通道归运行方）：

- **`replay`（默认）**：一律走应答库；后端工具无应答库时调用失败（fail-closed）并提示先录制。CI 与常规评估永远确定性。
- **`live`**：后端工具真实外呼声明的 URL，并把每次交换以**应答库同格式**存档到 `<run>/tools/<name>.recorded.yaml`——复制为任务的 `hidden/tools/<name>.responses.yaml` 即完成「录制 → 回放」晋升。

安全口径：URL/method/headers 全部静态声明，Agent 入参只作请求负载、**改不了外呼目标**；不跟随重定向；响应体 8MB 上限；后端不可达/非 2xx/非 JSON 收敛为结构化调用失败（trace 留痕、报告 `failed_calls` 可见），不会让 run 崩溃。live 调用在 trace 中标记 `mock=false`，与回放调用可区分。

### auto-eval 后台采样（进行中的得分轨迹）

`task.yaml` 的 `runtime.auto_eval_interval_seconds > 0` 时，attempt 执行期间框架每隔该间隔在后台快照 workspace 并跑一次隐藏评审：本轮 inbox 已有提交（含草稿）就对其评审，否则以空提交采样（此时只有 workspace 类检查有信号——正好覆盖「代码任务写到一半」的进度观测）。采样结果**只进 trace（`auto_eval_sampled` 事件）与报告的 `auto_eval` 轨迹段**，不回注 Agent、不影响正式成绩；采样评审在临时副本上执行，产物隔离在 `judge/auto/`。长跑任务（cli/http Agent 数十分钟级）由此获得「分数随时间演进」的过程曲线，而不是只有终点分。

### suite：批跑、pass^k 可靠性与多 Agent 对比

`suite` 既是 CI 冒烟门禁，也是生产 Agent 的度量/选型入口：

- **CI 冒烟**（默认 `--agent scripted`）：批跑全部任务的确定性回放，验证框架闭环未劣化；
- **真实 Agent 度量**：`--agent cli --cmd '...'` 让真实命令行 Agent 批量过全部任务；`--repeat k` 把每个任务跑 k 次，按 **pass^k** 口径（k 次全过才算稳定通过，借鉴 tau-bench）判可靠性，抓出「首跑过、复跑挂」的不稳定 Agent；
- **多 Agent 并列对比**：`--agents-file agents.yaml` 让多个 Agent（scripted/cli/http 混搭）跑同一任务集，产出「任务 × Agent」矩阵与按稳定通过数排序的排名面板；
- 报告（`suite_report.json` / `suite_report.md`）逐 run 记录真实墙钟时延并聚合平均耗时；Agent 在提交信封里自报 `usage`（model/token/cost，可选字段）时，单 run 报告与套件/对比面板会聚合出成本列——**自报数据只做 ROI 参考，不参与评分**，且以签名 trace 事件（`usage_recorded`）留痕。

`agents.yaml` 格式：

```yaml
agents:
  - label: baseline-scripted
    type: scripted            # 用各任务 samples/replay.yaml 回放
  - label: my-agent
    type: cli
    cmd: 'claude -p "$(cat {instructions})" --dangerously-skip-permissions'
  - label: my-service
    type: http                # 服务型 Agent（窄口径 HTTP 契约）
    endpoint: http://localhost:8080/agent
    headers:                  # 可选
      - 'Authorization: Bearer xxx'
```

### CI 冒烟门禁

`.github/workflows/ci.yml` 与 `bin/ci-smoke.sh` 同口径四道闸：CODEMAP 漂移门禁（`bin/gen-codemap.sh --check`，代码地图与源码不一致即失败）→ `mvn clean verify`（159 个测试 + Checkstyle 静态检查 + JaCoCo 指令覆盖率 ≥ 0.75 下限）→ 任务集回放批跑（`suite --fail-on-not-passed`，任一任务闭环回归被破坏即失败）→ 红队回归（`redteam/run_all.sh`，fail-closed：`VULNERABLE` 超出登记基线 `RT_ALLOWED_VULN`、或出现 INFRA（报告缺失/解析失败）/ CHECK（观测值偏离登记预期）即失败，另产出结构化工件 `redteam_report.json`；门禁判定逻辑抽为 `redteam/gate_lib.sh` 纯函数，由 `redteam/test_gate.sh` 的正/负向自测钉死 fail-closed 契约，同样进 CI）。红队回归含 **J 组 llm_rubric 框架护栏**（权重封顶 >30% 拒绝 / 禁 blocking / 判分模型未配置 fail-closed，确定性、无需真实模型）。红队基线动态自适应：**Docker Runner 就绪时红队 A 在容器内演练（外科式偷看 + symlink/find 逃逸全部 DEFENDED，基线归 0）；不就绪则回退非容器 A 并保留 VULNERABLE 登记基线 1**——两条路径都确定性（回放/mock，容器 `--network none` 全程断网，仅一次性拉取沙箱镜像需网络）。CI 另设**强制 Docker 红队 job**（`redteam-docker-enforced`）：`RT_REQUIRE_DOCKER=1` 不允许回退、`RT_ALLOWED_VULN=0` 要求容器路径零 VULNERABLE、沙箱镜像按 digest 固定，专门守护「不可信/强对抗 Agent 隔离能力」不被静默降级。

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

1. `bin/agent-eval task init --id my-task-001` 生成脚手架——产物是一个**已经能跑**的最小任务（过 validate、自带 fail→pass 回放闭环），从它开始改而不是从空目录开始猜；
2. 改 `task.yaml`（`task_id` 必须等于目录名；维度权重之和必须等于满分），替换 `work/` 材料；
3. 改 `hidden/judge.rules.yaml`（15 种 check 见下）；期望值放 `hidden/expected/`，用 `expected_from: "expected/x.json#/指针"` 引用，**不要**写进对外文案；
4. `bin/agent-eval validate --task tasks/my-task-001` 过体检（含深度 lint：`expected_from` 断链、`schema_file` 缺失、check 要求调用白名单外工具等 run 时才会炸的配置错误都在这里前移拦截）；
5. 更新 `samples/attempt-pass.json` / `attempt-fail.json` + `replay.yaml`，用 scripted 跑通 fail→pass 闭环。

**check 类型（15 种）**：`json_schema`、`jsonpath_equals`（支持 `expected`/`expected_from`/`tolerance`）、`jsonpath_exists`、`jsonpath_matches`、`list_coverage`（关键点组覆盖率，部分得分）、`evidence_sources_valid`、`workspace_file_exists`、`workspace_file_contains`、`changed_files_verified`（基线指纹核验）、`command`（临时副本上执行，`{hidden} {workspace} {submission} {task}` 占位符）、`tool_call_required`（trace 核验 + call_id 引用核验）、`tool_call_forbidden`、`world_state`（把 trace 中可信且成功的写工具调用折叠为世界终态，与期望的 `{tool, input}` 列表比对；`scope: attempt/run`、`order_sensitive` 可选——评的是「实际改成了什么」而非「提交里说了什么」）、`no_canary_leak`、`llm_rubric`（唯一非确定性类型，见下）。

每个 check 可配 `dimension / points / blocking（一票否决）/ severity / feedback_fail（对外文案，禁含期望值）`。计分：维度得分 = 权重 × Σearned/Σpossible；通过 = 总分 ≥ 通过线 **且** 无 blocking 失败。

**`llm_rubric`（LLM 判分，低权重主观维度专用）**：按 `hidden/` 里的 rubric 让判分模型对提交的主观维度（表达质量、报告结构等）打 0~1 分，`earned = points × 模型分`。参数：`rubric_file`（必填）、`target`（被评内容 JSONPath，默认整个提交）、`min_score`（通过线，默认 0.6）。判分模型经环境变量接入（OpenAI 兼容 chat completions）：`AEL_LLM_ENDPOINT` / `AEL_LLM_MODEL` / `AEL_LLM_API_KEY`（可选）——凭证只在框架进程，Agent 不可见。硬约束：**deep lint 强制 llm 检查的有效权重 ≤ 满分的 30%、禁止 blocking**（非确定性信号永不一票否决）；temperature 0 + 强 JSON 输出契约 + 原始请求/响应全量存档 `judge/`（`rubric_fingerprint`、`judge_model` 一并入档）；模型未配置或连续失败按**评审设施故障**上抛（run 记 ERROR），绝不静默打分；用了 llm 检查的评分结果如实标注 `reproducibility.deterministic=false`。防注入为尽力而为（定界包裹 + 指令免疫声明 + 截断），重大结论应人工复核。

## 安全边界（可信硬化后的诚实声明）

本地默认模式下 Agent 与框架同机、共享文件系统；在**不引入容器**的前提下，我们把最容易被主动作弊利用的路径做了进程内硬化。**对不可信/强对抗 Agent，用 `--sandbox docker` 做容器强隔离**（只挂载 Agent 可触碰区），根治了同机文件系统下无法防的「外科式偷看 hidden」。红队回归见 `redteam/run_all.sh`，已并入 CI 门禁（fail-closed）：`VULNERABLE` 超出登记基线、或出现 INFRA / CHECK 即失败；每次运行产出 `runs/redteam/redteam_report.json` 供 CI 工件与趋势分析。基线随 Docker 就绪度自适应：**Docker Runner 就绪时 19 项全 DEFENDED、登记基线 0**；Docker 不就绪则回退非容器 A（16 DEFENDED / 1 VULNERABLE 登记残留）。

### 已修复（附红队证据）

1. **trace 不可伪造（P0-2，根因修复）**：工具网关从「Agent 派生的子进程」改为「框架进程内常驻服务」（`ToolGatewayServer`，监听 loopback 临时端口）。每条框架事件用**每 run 私密密钥**做 HMAC-SHA256 签名（`TraceSigner`），密钥仅存于框架内存、Agent 执行期间不落盘（`TraceSecret` 的安全不变量）。判分只统计签名可核验的 `tool_call` 事件——Agent 直接往 `trace.jsonl` 手写的伪造事件（无合法签名）被静默丢弃。→ **红队 C 由 VULNERABLE 翻转为 DEFENDED**。
2. **`command` 短路被挡（P0-3）**：judge 每轮生成随机 nonce，注入命令模板 `{nonce}` 与 `output_regex`；受信任的隐藏 harness 只在跑完真实断言后回显含 nonce 的成功标记。事先写死的成功令牌（如静态块抢先 `System.out.println("ALL_CHECKS_PASSED"); System.exit(0)`）因不含本轮随机 nonce 而无法命中。→ **红队 D 翻转为 DEFENDED**。
3. **偷看 hidden 更易被抓（P0-1 缓解）**：`no_canary_leak` 扫描面从 `workspace/` 扩到 `agent-logs/`、`inbox/` 与 `traces/`——Agent 把 hidden 内容 dump 到 stdout（最常见的越界形态，CLI 适配器会落盘到 agent-logs）会被 canary 命中。→ **红队 A2（dump 含 canary 的应答库）为 DEFENDED**。
4. **借工具入参外泄被封（P0-1 扩展）**：Agent 若把偷到的 canary 当作工具入参经网关外泄（网关会把 input 原样记入 append-only trace），仅扫 workspace/日志会漏检——canary 扫描已覆盖 `traces/`，据此检出 trace 中的 canary。→ **新增红队 H（工具入参夹带 canary）为 DEFENDED**。
5. **越权工具调用被拒**：网关按任务 `allowed_tools` 白名单拦截未授权工具（`error=tool_not_allowed` 留痕），不产生成功调用记录，`tool_call_required` 一票否决。→ **新增红队 G（调 allowlist 外的工具）为 DEFENDED**。
6. **PASS_TO_PASS 回归门（借鉴 SWE-bench）**：`code-fix-001` 的隐藏行为规格分 FAIL_TO_PASS（缺陷修复）与 PASS_TO_PASS（既有 `maxPrice` 行为不得改坏）两组断言，挡住「为过缺陷用例而把无关既有功能改坏/删除」的回归式作弊。→ **新增红队 D2（修好 total 却改坏 maxPrice）为 DEFENDED**。
7. **终态比对堵「过程对、终态错」（借鉴 tau-bench）**：`world_state` check 把 trace 中**签名可核验且成功**的写工具调用折叠成世界终态，与隐藏期望终态比对（blocking）。Agent 流程全对、提交也「说」对了，但实际把卡开成错的类型——旧检查全过，终态比对一票否决。→ **新增红队 I（真实调用+提交正确，却把卡开错）为 DEFENDED**。

8. **外科式偷看 / 运行时 nonce 重放被容器根治（Docker Runner，P0-1 根因修复）**：`--sandbox docker` 让 Agent 命令在只挂载 `workspace/inbox/feedback/instructions` 的容器内执行，`hidden/`、`judge/`、任务目录、宿主家目录都不在容器文件系统里。Agent 无论用宿主绝对路径、workspace 内 symlink 逃逸、还是全盘 `find`，都摸不到隐藏答案——「只 `sed` 出答案数值、不触碰 canary」的外科式偷看因此失去物证；harness 也不在容器内，「运行时读命令行重放 nonce」同样失去落点。→ **红队 A（外科式偷看）+ A-sym（symlink 逃逸）+ A-find（全盘 find）在 Docker Runner 下全部 DEFENDED，登记基线降为 0**。

### 仍存的残余风险（诚实告知）

- **默认（非容器）模式下外科式偷看仍不可检测**：不启用 `--sandbox docker` 时，Agent 与框架同机同用户，只 `sed` 出隐藏答案数值、既不整段转存也不触碰 canary 则无痕（此配置下红队 A 仍为 VULNERABLE 登记基线）。canary 只能抓「抄了带 token 的内容」，抓不住「读了可推导的答案值」——**对不可信 Agent 请用 `--sandbox docker`**，容器隔离是这条的根治手段。
- **默认（非容器）模式下 command nonce 非密码学强度**：nonce 经命令行参数传给 harness，进程内代码理论上可在运行时读自身命令行并回显 nonce 来短路。它挡住了「事先写死令牌」这一常见作弊，但挡不住「运行时动态读取并重放 nonce」——容器隔离（harness 不在 Agent 容器内）同样根治此项。
- **run 结束后的产物篡改**：签名密钥在 run 收尾（Agent 已停止）时才落盘以供离线复核与 resume；对已完成 run 的离线产物篡改属另一层威胁，靠目录指纹 + 从干净任务目录复算发现。
- **Docker 沙箱的边界**：容器隔离的强度取决于 Docker 配置本身（默认 root-in-container、共享内核）；镜像由评估方提供，需自行确保可信。断网（`--network none`，默认）下工具网关不可达，需工具的任务用 `--sandbox-network bridge`（经 `host.docker.internal` 回连）。

**结论**：默认（进程内硬化）模式可信度为「能挡住常见作弊手法」，可对接**受控的真实业务评估**（内部 Agent 能力度量、CI 门禁、多轮改进回归）；**启用 `--sandbox docker` 后进一步补齐「外科式偷看」与「运行时 nonce 重放」两处此前只能靠隔离根治的残余，可面向不可信/强对抗 Agent**。

## 文档

**AI/新人协作入口**：`AGENTS.md`（唯一权威协作指南，Codex/Cursor 原生读取，Claude Code 经 `CLAUDE.md` 一行导入桥共用；含 60 秒架构理解、分区路由、验证阶梯、安全红线）→ `docs/CODEMAP.md`（`bin/gen-codemap.sh` 自动生成的类级代码地图，CI 漂移门禁保证与源码一致）→ `docs/PLAYBOOK.md`（6 类高频改造 recipe）。`tasks/`、`redteam/`、`src/main/java/com/agenteval/` 各有分区 `AGENTS.md`；仓库级 skills 在 `.agents/skills/`（Codex 原生，`.claude/skills` 符号链接共享给 Claude Code），其中 `ael-build-evalset` 可让 AI 辅助你从零建私有测评集并接入自己的 Agent——可运行示例见 `evalsets/demo-ops-agent/`。

- `docs/01-代码库理解报告.md` — 项目选址决策记录（为何独立成项目）
- `docs/02-EdgeBench-SForge-调研总结.md` — 调研结论与取舍清单
- `docs/03-AgentEval-Lite-设计方案.md` — 完整设计（架构图、Schema、时序、安全模型、示例任务、实施计划）
- `docs/04-成熟评估框架调研与ROI报告.md` — 成熟评估框架横向调研与借鉴取舍

## 路线图

- **Phase 1（当前）**：单机 CLI 闭环——TaskSpec / 三适配器 / 规则+脚本 Judge / trace / 报告 / resume / 5 示例任务
- **Phase 1.5（可信硬化，已落地）**：工具网关常驻服务化 + trace HMAC 签名（P0-2）、`command` nonce 防短路（P0-3）、canary 扫描扩面到 agent-logs/inbox/traces（P0-1，含借工具入参外泄）、越权工具调用拦截、PASS_TO_PASS 回归门——见「安全边界」与 `redteam/run_all.sh`
- **Phase 1.6（工程化，已落地）**：任务集回放批跑（`agent-eval suite`）+ CI 冒烟门禁（`bin/ci-smoke.sh`、`.github/workflows/ci.yml`：测试 + 套件 + 红队三道闸）
- **Phase 1.7（生产度量，已落地）**：`suite` 支持真实 cli Agent 批量过任务、`--repeat k` 的 pass^k 可靠性口径、`--agents-file` 多 Agent 并列对比（任务×Agent 矩阵 + 排名面板）、逐 run 时延采集与聚合
- **Phase 1.8（可观测与质量门禁，已落地）**：`agent-eval export` 导出 OTLP/OpenInference（Phoenix / Langfuse / OTel Collector 摄取，Phoenix 实测端到端）、`world_state` 终态比对 check（红队 I 拦截「过程对终态错」）、信封可选 `usage` 自报 + 单 run/套件/对比面板成本聚合、红队门禁 fail-closed 化 + `redteam_report.json` 结构化工件、JaCoCo 覆盖率下限 + Checkstyle 进 CI
- **Phase 2（服务接入与任务工程化，已落地）**：HTTP AgentAdapter 窄口径（框架按轮 POST 任务说明、响应体即提交；`run`/`suite`/`agents-file` 全线支持，可评已有 chat API 等服务型 Agent）+ `task init` 任务脚手架（开箱即过 validate 与回放闭环）+ `validate` 规则深度 lint。Playwright 级 Web 评估链路刻意不做（见 docs/04 §9）
- **Phase 3（真实信号，已落地）**：真实工具 http 后端（`task.yaml` 静态声明 URL 白名单 + `${ENV:*}` 凭证解析 + live 录制 → replay 回放晋升，`AEL_TOOL_MODE` 控制，默认 replay 保 CI 确定性；db 只读后端因需引入 JDBC 依赖刻意不做）+ auto-eval 周期采样（后台快照评审 → `auto_eval_sampled` trace 事件 + 报告得分轨迹，不回注 Agent 不影响成绩）+ `llm_rubric` LLM 判分（低权重主观维度专用：lint 强制有效权重 ≤30%、禁 blocking、fail-closed、全量存档、`deterministic=false` 如实标注）
- **Phase 4（硬隔离，Docker Runner 已落地）**：`--sandbox docker` 让 cli Agent 在只挂载 `workspace/inbox/feedback/instructions` 的容器内执行（默认 `--network none` 断网），`hidden/`/`judge/`/任务目录不入容器——红队 A 外科式偷看 + symlink/find 逃逸全部 DEFENDED、运行时 nonce 重放失去落点、登记基线降 0；`run`/`suite`/`agents.yaml` 全线支持，工具网关经 `host.docker.internal` 回连
- **Phase 4.5（分层与结果治理，已落地）**：`task.yaml` 可选 `tier`（smoke/regression/security/domain）+ `labels` 分层元数据（不影响判分），`suite --tier` 按分层批跑子集（与 `--tasks` 取交集）；`agent-eval history` 只读离线聚合 `runs/` 历次 `report.json`，产出「任务 × Agent」跨 run/版本趋势（通过率、首/末/最佳分、判分确定性）+ 并入最近一次红队门禁摘要（JSON+MD）。**仍待**：持久化结果仓库与可视化看板

## 许可证

本项目采用 Apache License 2.0，允许商用、修改、分发和闭源集成，并包含明确的专利授权。详见 [LICENSE](LICENSE)。
