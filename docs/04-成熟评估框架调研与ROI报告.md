# AgentEval-Lite：成熟评估框架调研 + PoC + ROI 路线报告

> 调研日期：2026-07-07 · 方法：官方文档/GitHub + 本机最小 PoC 实跑（4 个）+ 现有代码逐包核对。
> 本机环境：Python 3.13.12 / Node v22.22 / Docker 27.5.1 / Java 21 / Maven 3.9.11；`OPENAI_API_KEY` 存在但上游 88code 中转 `429 RPM 上限`（真在线判分不稳定），故所有 PoC 走**离线确定性**路径，保证可复现。
> PoC 产物：`research/poc/`（Inspect、promptfoo、garak、Phoenix 四套可重跑脚本 + 输出）。

---

## 1. 执行摘要

**核心结论：你不是"从 Demo 起步"，AgentEval-Lite 已经是 Phase 1.5 的准 MVP。** 逐包核对代码后，业界成熟框架里"最该先做"的那批能力，你**已经做完并且做得比多数开源框架更硬**：

- 结构化提交 + 双层 JSON Schema 校验（`submission.envelope` + 6 个分型 schema，draft 2020-12）——**已做**；
- 隐藏判分 + 14 种确定性 check（含 `world_state` 终态比对）+ 可选脚本 judge——**已做**；
- Trace JSONL + **每 run HMAC 签名**（判分只认可核验事件，Agent 无法伪造 tool_call）——**已做，且强于 promptfoo/Inspect 的裸 trace**；`export` 可导出 OTLP/OpenInference 外接看板——**已做**；
- 工具网关 allowlist + `tool_call_required(blocking)` + `call_id` 引用核验——**已做**；
- canary 泄露探针、指纹可复现判分、resume、stop-hook——**已做**；
- 红队回归套件——**已做**，由 8 项深化至 14 项（13 DEFENDED / 1 登记基线残留）并并入 CI 门禁。

因此本报告的价值不在"教你补齐地基"，而在**纠偏 ROI 方向**：把精力从"再造已有轮子"转向三个真实缺口——(1) **深化红队 suite**（尤其注入/越权/外科式偷看），(2) **多 Agent 对比 + 任务集批跑 + CI smoke 打包**，(3) **可选外接 trace 平台 / LLM judge**。

### 四个 PoC 全部实跑通过（非文档臆测）

| PoC | 框架 | 结果 | 对 AgentEval-Lite 的意义 |
| --- | --- | --- | --- |
| A | Inspect AI 0.3.244 | 诚实 Agent=1.0 / 作弊 Agent=0.0 | 证明"工具真调用才给分"的 blocking 语义在成熟框架里的标准写法——与你的 `tool_call_required` 等价 |
| B | promptfoo 0.121.17 | 2 通过 / 1 失败，**退出码 100** | 证明可做 prompt/输出回归 + CI 门禁（非零退出即挡 PR），且**离线可复现** |
| C | garak 0.15.1 | promptinject 探针 512 attempts 跑完，报告 JSONL 可解析 | 证明红队探针/检测器管线可离线驱动，可作为 redteam 语料来源 |
| D | Arize Phoenix 17.19 + OTel | span 入库（2×`POST /v1/traces→200`），trace 断言正例 True/反例 False | 证明 OpenInference/OTel 是 trace 的通用交换格式，你的自研 trace 可低成本外接看板 |

**一句话建议**：地基已稳，下一步投入应压在"红队深化 + 多 Agent/suite/CI 打包"上；trace 平台与 LLM judge 用"借鉴 schema + 可选外挂"的方式做，不要重度绑定。

---

## 2. 调研范围与方法

- **读了什么**：promptfoo、Inspect AI、DeepEval、OpenAI Evals、garak、PyRIT、Langfuse/Phoenix/LangSmith、SWE-bench(Verified)、tau/tau2-bench、WebArena/OSWorld/AgentBench/GAIA、Ragas 的官方 GitHub / 文档 / Quickstart / 论文。
- **跑了什么**：见 §1 表与 §5。凡"未运行"的框架，卡片里明确写"仅文档调研"。
- **不把营销当能力**：SaaS 宣传的"自动发现漏洞/一键评估"若未经我实跑，一律标注为"文档声称，未验证"。
- **RAG 类**：按你的指示（"RAG 不是重点"）**只做文档调研，不做 PoC**。

---

## 3. 框架分类总览

| 类别 | 代表 | 与 AgentEval-Lite 的关系 |
| --- | --- | --- |
| LLM/Prompt 输出评估 | promptfoo、DeepEval、OpenAI Evals(弃)、MLflow、Braintrust/LangSmith/Humanloop(SaaS) | **借鉴断言模型 + 可外挂做 prompt 回归**；核心判分仍自研 |
| RAG 评估 | Ragas、Phoenix Evals、TruLens、LlamaIndex/Haystack eval | 只借鉴 faithfulness/引用核验思想到 `evidence` 字段；**不搬** |
| Agent 评估/Benchmark | Inspect AI、SWE-bench、tau/tau2-bench、AgentBench/WebArena/OSWorld/GAIA | **Inspect/tau/SWE-bench 借鉴范式**；重环境 benchmark 暂不碰 |
| Observability/Trace | Phoenix、Langfuse、LangSmith、Helicone、Weave、OTel | **借鉴 OpenInference schema，可选外接看板**；自研 trace 保留 |
| Safety/Red Team | garak、PyRIT、promptfoo redteam、Giskard | **garak/promptfoo 作为红队语料/外挂**；核心越权检测自研 |
| Benchmark Runner | SWE-bench harness、Inspect eval | 借鉴 patch-apply/FAIL_TO_PASS 判分范式 |
| 自研可借鉴组件 | 上述全部的 schema/断言/trace 格式 | 见 §11 差距矩阵 |

---

## 4 & 5. 每个框架卡片（含优点/不足/PoC）

> 字段固定顺序，PoC 字段绝不留空。

### 4.1 promptfoo 【PoC 已完成】

```text
框架名称：promptfoo
官网/GitHub：promptfoo.dev · github.com/promptfoo/promptfoo（MIT；2025 起并入 OpenAI，仍开源）
定位：CLI 优先的 LLM 应用评估 + 红队扫描器（声明式 YAML）
当前活跃度：极高（宣称 10M+ 用户；OpenAI 官方推荐为 Evals 弃用后的迁移目标）
核心能力：providers 多模型对比 / 断言(equals,contains,regex,is-json,cost,llm-rubric,javascript,python) / redteam(50+ 漏洞类型,OWASP LLM Top10) / CI 集成 / 自定义 provider
适合评估对象：prompt、RAG、agent（HTTP/脚本形式挂进来的"被测系统"）
是否支持 Agent：间接（把 agent 当黑盒 target 打，不深入 agent 内部步骤）
是否支持工具调用 trace：弱（可读 OTel trace 做断言，但非 agent-step 原生）
是否支持 hidden judge：否（断言与用例同文件，无 work/hidden 隔离）
是否支持结构化 submission：部分（is-json + json schema 断言）
是否支持多轮提交：否（单轮 eval 为主）
是否支持 CI：是（非零退出码门禁，本 PoC 实测退出码=100）
是否支持本地运行：是（100% 本地，prompt 不出机器）
是否支持 SaaS：是（promptfoo.app 可选分享）
学习成本：低（YAML 声明式）
接入成本：低（1-2 天）
维护成本：低
优点：声明式断言模型极干净；deterministic + LLM-rubric 混用；红队开箱即用；CI 友好；自定义 provider 可把任意系统变 target
不足：无 work/hidden 隔离；无多轮反馈/best-of-N；agent 内部 step 不透明；redteam 生成默认要 OpenAI key（不给则代理到其云）
对 AgentEval-Lite 的借鉴价值：高——断言分类法（确定性 vs 模型辅助）可直接映射到你的 check 分类；可作为"prompt 层回归"的外挂
是否建议集成：可选集成（作为 prompt-regression + redteam 语料生成的外部工具，用你的 cli adapter 挂）
是否建议只借鉴思想：断言分类法必借鉴
是否建议暂时不碰：否
PoC 是否完成：是
PoC 结果：离线自定义 provider（echo_cardgen，无 LLM）3 用例：GOLD→PLATINUM 通过、SILVER→GOLD 通过、无依据猜测→失败；`promptfoo eval` 退出码=100（有失败即非零，CI 门禁成立），结论 100% 可复现
证据：research/poc/promptfoo/{promptfooconfig.yaml,echo_provider.js,last_eval_output.txt}
```

**关键洞察**：promptfoo 的断言体系正好把"确定性 check"和"LLM-as-judge"分成两层——这与 AgentEval-Lite `RulesJudge` 的 14 种确定性 check + 未来 LLM judge 维度是同构的。你不需要它做核心判分，但它的 **YAML 断言分类法值得抄进你的 `judge.rules.yaml` 文档**。

---

### 4.2 Inspect AI 【PoC 已完成 · 本次最推荐借鉴对象】

```text
框架名称：Inspect AI（inspect_ai）
官网/GitHub：inspect.aisi.org.uk · github.com/UKGovernmentBEIS/inspect_ai（英国 AI 安全研究所 AISI 出品）
定位：生产级 LLM/Agent 评估框架（Dataset→Solver→Scorer→Task）
当前活跃度：极高（政府机构维护，200+ 内置 eval，频繁发版，本机装到 0.3.244）
核心能力：Task/Sample/Dataset/Solver/Scorer 原语 / 多轮 agent + 工具循环 / 内置沙箱(process jail/Docker/K8s) / 模型评分 / .eval 日志 + Inspect View 看板 / mockllm 离线
适合评估对象：LLM 单轮、tool-using agent、多轮对话、编码 agent
是否支持 Agent：是（ReAct/tool-loop 一等公民）
是否支持工具调用 trace：是（每个 sample 的 messages/tool_calls 全留痕，可写 scorer 核验）
是否支持 hidden judge：部分（scorer 是私有逻辑，但无强制文件级 work/hidden 隔离）
是否支持结构化 submission：部分（target/output 比对，需自定义 scorer 做 schema）
是否支持多轮提交：是（solver 可多步循环 + epochs）
是否支持 CI：是（inspect eval CLI + 退出状态）
是否支持本地运行：是
是否支持 SaaS：否（纯本地/自托管）
学习成本：中（概念清晰但原语多）
接入成本：中（Python 栈；借鉴模式 1 周内，深度集成到 Java 栈需桥接）
维护成本：低-中
优点：架构是 agent-eval 的教科书级参考；sandbox 内置；mockllm 让离线单测极方便；scorer 可访问 sandbox；日志格式规范可回放
不足：Python 生态（你的核心是 Java）；无你那种 HMAC 防伪 trace；work/hidden 隔离靠约定而非文件边界
对 AgentEval-Lite 的借鉴价值：极高——Solver/Scorer 分离、scorer 核验 tool_call、epochs/多轮、sandbox 分级，都是你 Phase 2-4 的直接蓝本
是否建议集成：可选（若愿意接受 Python 侧，可用它跑 LLM/agent 层，把结果回填你的 report）
是否建议只借鉴思想：强烈建议——scorer 设计、tool-call 核验模式、日志可回放
是否建议暂时不碰：否
PoC 是否完成：是
PoC 结果：离线 mockllm 注入两种 agent 行为，自定义 scorer 复刻你的 tool_call_required(blocking)：诚实 agent（真发 user_lookup 调用 + 答对）accuracy=1.0；作弊 agent（不调工具直接蒙对答案）accuracy=0.0——证明"工具没真调用即判负"可干净实现
证据：research/poc/inspect_toolcall_eval.py（可重跑）
```

**关键洞察**：Inspect 的 `Scorer` 能读取整条 `state.messages` 并断言"某工具是否真被调用"，这与你在 `RulesJudge` 里核验 HMAC 签名的 tool_call 事件是同一件事的两种实现。**Inspect 是本次调研里与 AgentEval-Lite 目标最贴合的架构参考**——建议把它的 Solver/Scorer/epochs/sandbox 分级当作你 Phase 2-4 的设计对照表。

---

### 4.3 garak 【PoC 已完成】

```text
框架名称：garak（Generative AI Red-teaming & Assessment Kit）
官网/GitHub：garak.ai · github.com/NVIDIA/garak（NVIDIA 维护，Apache-2.0，长期支持）
定位：LLM 漏洞扫描器（"LLM 界的 nmap/metasploit"）
当前活跃度：高（NVIDIA 官方项目，本机装到 0.15.1）
核心能力：probes(攻击)×detectors(命中判定)×generators(目标)×buffs(变体) / promptinject、encoding、gcg、DAN、latentinjection、hallucination、toxicity / HTML+JSONL 报告
适合评估对象：单轮"文本进文本出"的模型/对话系统
是否支持 Agent：弱（面向模型层，非 agent 多步/越权）
是否支持工具调用 trace：否
是否支持 hidden judge：否
是否支持结构化 submission：否
是否支持多轮提交：部分（atkgen/reactive 探针多轮，但目标是攻破而非评分）
是否支持 CI：部分（可脚本化跑 + 解析 JSONL）
是否支持本地运行：是（可对本地/HF/REST 目标）
是否支持 SaaS：否
学习成本：低-中
接入成本：低（pip 装即用）
维护成本：低
优点：探针库大且权威（PromptInject/GCG/AutoDAN 等）；报告结构化 JSONL 易解析；detector 抽象清晰
不足：面向"模型输出安全"，不测"agent 越权调用/hidden 文件访问/data exfiltration via tool"这类 AgentEval 关心的系统级风险；ASR 依赖 detector 质量
对 AgentEval-Lite 的借鉴价值：中——probe/detector 分离思想可借鉴到你的 redteam 用例组织；探针文本可作为你 prompt-injection 任务的语料源
是否建议集成：可选（作为注入语料/回归语料的外部来源，不作为核心裁决器）
是否建议只借鉴思想：probe×detector 组织法值得借鉴
是否建议暂时不碰：否（但优先级低于自研越权检测）
PoC 是否完成：是
PoC 结果：`garak --model_type test.Repeat --probes promptinject.HijackHateHumans` 跑完，512 次 attempt，detector=AttackRogueString，报告 JSONL 6 类 entry 可解析（start_run/init/attempt×512/eval/completion/digest）。注：目标是 test.Repeat 镜像生成器，ASR=100% 只证明"探针+检测器管线可离线驱动并出结构化结果"，非真实模型脆弱性
证据：research/poc/garak_out/rt.report.jsonl（2.4MB，可解析）
```

**诚实边界**：garak 测的是"模型能不能被诱导说坏话/被注入劫持"，**不测**"agent 越权调了不该调的工具""偷读了 hidden 文件"——后者恰是 AgentEval-Lite 的红队核心（你的 A/C/D/E 类攻击）。所以 garak 是**语料来源**，不是能替代你 redteam suite 的东西。

---

### 4.4 PyRIT 【仅文档调研，未运行】

```text
框架名称：PyRIT（Python Risk Identification Tool for GenAI）
官网/GitHub：github.com/microsoft/PyRIT（微软 AI 红队出品）
定位：GenAI 红队自动化编排框架（targets×orchestrators×converters×scorers×memory）
当前活跃度：高（微软 AI 红队 100+ 实战用；持续维护）
核心能力：单轮/多轮攻击编排 / RedTeamingOrchestrator（攻击 LLM vs 目标 LLM + scorer 循环）/ converters 变体 / memory 维护多轮状态
适合评估对象：基础模型 + 其应用（Copilot 类）
是否支持 Agent：部分（多轮编排接近 agent 对抗，但仍偏模型层）
是否支持工具调用 trace：否
是否支持 hidden judge：否
是否支持结构化 submission：否
是否支持多轮提交：是（multi-turn orchestrator 是卖点）
是否支持 CI：部分
是否支持本地运行：是
是否支持 SaaS：否（可选接 Azure AI Content Safety）
学习成本：中-高（组件多，编排概念重）
接入成本：中（Python；多轮编排需攻击模型 API，本机 429 限流下不适合实跑）
维护成本：中
优点：多轮自适应攻击强；scorer 可插 ML 分类器/LLM/Azure 内容过滤；工业级实战验证
不足：比 garak 重；面向模型对抗非 agent 系统越权；需要攻击侧 LLM（当前 API 限流不稳定）
对 AgentEval-Lite 的借鉴价值：中——多轮"攻击→打分→再攻击"编排思想，可借鉴到你未来的自适应 redteam
是否建议集成：暂不（比 garak 重，且核心不对口）
是否建议只借鉴思想：是（多轮红队编排）
是否建议暂时不碰：当前阶段可暂不碰
PoC 是否完成：否
PoC 结果：未运行——多轮编排需稳定的攻击侧 LLM，而本机 OpenAI 中转当前 429 RPM 限流，实跑不可复现，故仅文档调研
证据：github.com/microsoft/PyRIT README + arXiv:2410.02828
```

---

### 4.5 DeepEval 【仅文档调研，未运行 · 因 LLM 判分依赖在线模型】

```text
框架名称：DeepEval（confident-ai/deepeval）
官网/GitHub：deepeval.com · github.com/confident-ai/deepeval
定位："LLM 界的 pytest"——单测式 LLM 评估
当前活跃度：高
核心能力：G-Eval(LLM-as-judge 自定义准则) / DAG 确定性判分 / 14+ 内置指标(hallucination,answer relevancy,faithfulness,bias,toxicity) / pytest 集成(`deepeval test run`) / red-teaming
适合评估对象：LLM 输出、RAG、对话
是否支持 Agent：部分（component-level trace 可评 tool/子 agent）
是否支持工具调用 trace：部分（@observe instrument 后可评 span）
是否支持 hidden judge：否
是否支持结构化 submission：部分
是否支持多轮提交：部分（conversational metrics）
是否支持 CI：是（pytest + assert_test，一等公民）
是否支持本地运行：是（指标本地跑，判分模型可本地/远程）
是否支持 SaaS：是（Confident AI 平台）
学习成本：低（会 pytest 就会）
接入成本：低-中
维护成本：低
优点：pytest 心智模型极亲切；G-Eval 做 rubric 判分方便；CI 一条命令
不足：多数强指标要 LLM judge（在线依赖）；无 work/hidden 隔离；agent 深度评估弱于 Inspect
对 AgentEval-Lite 的借鉴价值：中——`assert_test` 门禁 + G-Eval rubric 的写法，可借鉴到你 Phase 3 的 LLM judge 维度
是否建议集成：暂不（判分模型在线依赖，且核心自研已够）
是否建议只借鉴思想：是（pytest 门禁 + rubric 结构）
是否建议暂时不碰：核心判分不碰，rubric 思想可借鉴
PoC 是否完成：否
PoC 结果：未运行——G-Eval/多数指标需在线 LLM judge，本机 API 429 限流不可复现；确定性 DAG 指标可离线但与 promptfoo PoC 重叠，故不重复实跑，仅文档调研
证据：deepeval.com/docs/evaluation-unit-testing-in-ci-cd
```

---

### 4.6 OpenAI Evals 【仅文档调研 · 建议暂时不碰】

```text
框架名称：OpenAI Evals（openai/evals + 托管 Evals API）
官网/GitHub：github.com/openai/evals
定位：注册表式 YAML eval + 托管评估 API
当前活跃度：低且在弃用——官方公告：2026-10-31 只读，2026-11-30 关停；官方推荐迁移到 promptfoo
核心能力：oaieval CLI 本地跑注册表 eval / 托管 API graders
适合评估对象：LLM 输出
是否支持 Agent：否
是否支持工具调用 trace：否
是否支持 hidden judge：否
是否支持结构化 submission：部分
是否支持多轮提交：否
是否支持 CI：部分
是否支持本地运行：是（oaieval）
是否支持 SaaS：是（即将关停）
学习成本：中
接入成本：中
维护成本：高（平台关停风险）
优点：历史资产多
不足：**正在关停**；OpenAI 自己都推 promptfoo
对 AgentEval-Lite 的借鉴价值：低
是否建议集成：否
是否建议只借鉴思想：否（用 promptfoo 替代）
是否建议暂时不碰：是
PoC 是否完成：否（平台弃用，实跑无意义）
PoC 结果：不适用——已官宣关停时间线，投入即负 ROI
证据：developers.openai.com/api/docs/guides/evals（弃用公告）+ cookbook「Moving from OpenAI Evals to Promptfoo」
```

---

### 4.7 Observability/Trace 平台：Phoenix / Langfuse / LangSmith 【Phoenix PoC 已完成】

```text
框架名称：Arize Phoenix（+ 对比 Langfuse / LangSmith）
官网/GitHub：phoenix.arize.com · github.com/Arize-ai/phoenix（Elastic License 2.0，自托管免费无功能阉割）
定位：OTel 原生的 LLM/agent 可观测 + 评估平台
当前活跃度：高（本机装到 17.19.0）
核心能力：OpenInference 语义约定的 span / tool-call/agent/retriever span kind / OTLP 摄入 / Phoenix Evals / 单进程易部署
适合评估对象：LLM/RAG/agent trace
是否支持 Agent：是（AGENT/TOOL/CHAIN span kind）
是否支持工具调用 trace：是（本 PoC 实测：tool span 记录 name/args/output）
是否支持 hidden judge：否（它是观测层，非判分隔离）
是否支持结构化 submission：否
是否支持多轮提交：不适用
是否支持 CI：部分（Evals 可脚本化）
是否支持本地运行：是（本 PoC 单进程起在 6006）
是否支持 SaaS：是（Arize AX 商业版）
学习成本：低-中
接入成本：低（pip + OTLP，本 PoC 当天跑通）
维护成本：中（自托管要维护存储）
优点：OTel/OpenInference 是**厂商中立交换格式**——instrument 一次，后端随便换（Phoenix/Langfuse/Jaeger/Datadog）；单进程易起
不足：观测≠判分，替代不了你的 HMAC 防伪 trace；自托管有存储运维
对 AgentEval-Lite 的借鉴价值：高——**借鉴 OpenInference span schema**（tool.name/tool_call.arguments/output.value/span.kind），让你的 trace.jsonl 可选导出到任意 OTel 后端看板
是否建议集成：可选（Phase 3 做"trace 导出适配器"，把你已签名的事件转 OpenInference span 发 OTLP）
是否建议只借鉴思想：schema 必借鉴
是否建议暂时不碰：核心判分不碰
PoC 是否完成：是
PoC 结果：Phoenix 单进程起在 localhost:6006；用 OTel SDK + OpenInference 打了 agent span + tool span；内存导出器断言"user.lookup 被真实调用=True、payment.charge=False"；同批 span 经 OTLP 发往 Phoenix，服务端日志 2×`POST /v1/traces→200`（span 确实入库，非仅"导出已启用"）
证据：research/poc/phoenix_trace_eval.py + Phoenix 服务端 access log
```

#### 三大平台选型（文档调研 + Phoenix 实跑）

| 平台 | 开源/自托管 | OTel | 适合你 | 结论 |
| --- | --- | --- | --- | --- |
| **Langfuse** | MIT，全自托管无阉割 | 原生摄入 | 需数据主权、框架中立 | **首选外接**（若要看板）；运维要 Postgres+ClickHouse+Redis+S3 |
| **Phoenix** | Elastic 2.0，自托管免费 | OpenInference 原生 | 单进程轻量、RAG/离线 evals | **首选借鉴 schema**；已实跑最省事 |
| **LangSmith** | 闭源 SaaS，自托管仅企业版 | 部分摄入 | 深度绑 LangChain/LangGraph | **不建议**（你非 LangChain 栈，且闭源绑定） |

**自研 trace vs 外接平台的 ROI**：你的 trace 有**别人没有的东西**——每 run HMAC 签名 + 判分只认可核验事件。外接平台**给不了**这层防伪，它给的是"看板 + 跨 run 检索 + cost/latency 聚合"。所以正确姿势是**保留自研 trace 做判分事实源，加一个可选的 OpenInference 导出适配器做可视化**，而不是二选一。

---

### 4.8 Agent Benchmark：SWE-bench / tau-bench / 重环境类 【仅文档调研】

```text
框架名称：SWE-bench / SWE-bench Verified
官网/GitHub：swebench.com · github.com/SWE-bench/SWE-bench
定位：真实 GitHub issue 修复基准（patch→docker 跑测）
当前活跃度：极高（前沿模型主战场，Verified=500 题人工校验子集）
核心能力：git apply 打补丁 → 跑 FAIL_TO_PASS（证明真修了）+ PASS_TO_PASS（证明没弄坏）→ docker 隔离 → % Resolved
是否支持 Agent：是（coding agent）
是否支持工具调用 trace：否（黑盒判 patch）
是否支持 hidden judge：是（测试即隐藏验收，答案不随题给）
是否支持结构化 submission：是（predictions.jsonl：instance_id/model_patch）
是否支持多轮提交：否（一次性 patch）
是否支持 CI/本地：本地 docker
学习成本/接入/维护：中/高（海量 docker 镜像 + 磁盘）/中
优点：**patch-apply + 双向测试门（FAIL_TO_PASS/PASS_TO_PASS）是判分范式黄金标准**；docker 保可复现
不足：重（磁盘/镜像）；仅代码域；2026-04 UC Berkeley 证明含 SWE-bench 在内的 8 大 benchmark 均可被 reward-hack 到近满分（pytest hook 强制通过等）
对 AgentEval-Lite 的借鉴价值：高（思想）——你的 code-fix-001 已用 command check 编译执行，**建议再补 PASS_TO_PASS 式回归门**（改对了但别弄坏别处）
是否建议集成：否（太重）
是否建议只借鉴思想：是（FAIL_TO_PASS/PASS_TO_PASS 双门 + reward-hack 防御）
是否建议暂时不碰：完整复刻不碰
PoC 是否完成：否
PoC 结果：未运行——完整 harness 需拉取 GB 级 docker 镜像与数据集，成本远超"最小 PoC"边界；判分范式已通过阅读 harness 源码（run_evaluation.py）确认
证据：swebench.com/SWE-bench/reference/harness + github run_evaluation.py（GIT_APPLY_CMDS/FAIL_TO_PASS）
```

```text
框架名称：tau-bench / τ²-bench（Sierra）
官网/GitHub：taubench.com · github.com/sierra-research/tau2-bench（MIT，1.5k★，持续维护到 2026-06）
定位：Tool-Agent-User 交互基准（客服域：airline/retail/telecom/banking）
当前活跃度：高（tau3 已加 voice + banking RAG）
核心能力：**对话结束后比对数据库终态 vs 标注目标态**（而非比对话文本）/ pass^k 可靠性度量 / policy 遵循 / 用户模拟器
是否支持 Agent：是（tool + user 双方）
是否支持工具调用 trace：是
是否支持 hidden judge：是（终态比对是隐藏验收）
是否支持结构化 submission：是（世界状态）
是否支持多轮提交：是（多轮对话）
是否支持 CI/本地：本地（需 agent LLM + user LLM）
学习成本/接入/维护：中/中/中
优点：**"比世界终态而非比嘴"彻底封杀自说自话**；pass^k 度量可靠性（同任务多跑，全过才算稳）；policy 文档遵循评估
不足：需两个 LLM（agent+user 模拟器），当前 API 限流下难实跑；域固定
对 AgentEval-Lite 的借鉴价值：极高（思想）——(1) **终态比对**思想可强化你的 api_payload/tool_call 判分；(2) **pass^k**可作为你多 Agent 对比的可靠性指标
是否建议集成：否
是否建议只借鉴思想：强烈建议（终态比对 + pass^k）
是否建议暂时不碰：完整复刻不碰
PoC 是否完成：否
PoC 结果：未运行——需稳定 agent-LLM + user-LLM 双模型，本机 429 限流不可复现；核心范式（DB 终态比对 + pass^k）已通过论文 arXiv:2406.12045 / 2506.07982 确认
证据：github.com/sierra-research/tau2-bench + 两篇论文
```

```text
框架名称：WebArena / OSWorld / AgentBench / GAIA（重环境 benchmark 群）
定位：浏览器/桌面/多环境 agent 基准
当前活跃度：高但方法学争议大
关键事实：2026-04-12 UC Berkeley RDI 证明 8 大 agent benchmark（含 WebArena/OSWorld/GAIA/Terminal-Bench）**全部可被 reward-hack 到近满分**（配置泄露、DOM 注入、VM 状态篡改、公开答案查表、归一化碰撞等）
是否支持 hidden judge / 多轮 / trace：各异，但普遍重
对 AgentEval-Lite 的借鉴价值：低（当前阶段）——环境太重（浏览器/VM/docker 集群）
是否建议集成/借鉴/不碰：**暂时不碰**（唯一值得吸收的是"reward-hacking 威胁模型"——正好印证你 README 里"外科式偷看"残余风险的判断）
PoC 是否完成：否
PoC 结果：未运行——需浏览器/VM/大规模 docker 环境，远超轻量框架当前阶段；其"防作弊"教训已被你现有安全设计覆盖
证据：leaderboard.steel.dev + moogician.github.io「How We Broke Top AI Agent Benchmarks」
```

---

### 4.9 RAG 评估（仅文档调研 · 按你指示不做 PoC）

- **Ragas**：context precision/recall、faithfulness、answer relevance、引用核验；对口 RAG 应用；对 AgentEval-Lite 只借鉴 **faithfulness/引用核验思想 → 强化 `evidence_sources_valid` check**，不搬框架。
- **Phoenix Evals / TruLens / LlamaIndex/Haystack eval**：同类，均"仅文档调研"。
- **与 AgentEval-Lite 的关系**：你的 `evidence` 字段（type/source/quote_or_ref）+ `evidence_sources_valid` check 已经是"引用可核验"的轻量版；RAG 框架的 faithfulness 是"答案是否被 context 支撑"，可作为**未来 doc-analysis 类任务的 LLM judge 维度借鉴**，当前不做。

---

## 6. ROI 评分表

> 公式：`ROI = Impact*0.30 + Cost*0.20 + RiskReduction*0.25 + Reusability*0.15 + Fit*0.10`（分数 1-5，Cost 越高=成本越低）

### 6.1 框架 ROI（对"是否值得投入到 AgentEval-Lite"）

| 框架 | Impact | Cost | Risk↓ | Reuse | Fit | **ROI** | 结论 |
| --- | :--: | :--: | :--: | :--: | :--: | :--: | --- |
| **Inspect AI** | 5 | 4 | 4 | 4 | 4 | **4.30** | 借鉴架构（Solver/Scorer/epochs/sandbox） |
| **promptfoo** | 4 | 5 | 4 | 4 | 4 | **4.20** | 可选外挂（prompt 回归 + redteam 语料） |
| **tau/tau2-bench** | 4 | 3 | 4 | 3 | 4 | **3.65** | 借鉴思想（终态比对 + pass^k） |
| **Phoenix** | 3 | 4 | 3 | 3 | 3 | **3.20** | 借鉴 OpenInference schema + 可选导出 |
| **SWE-bench Verified** | 4 | 2 | 4 | 2 | 3 | **3.20** | 借鉴 FAIL_TO_PASS/PASS_TO_PASS 双门 |
| **DeepEval** | 3 | 4 | 3 | 3 | 3 | **3.20** | 借鉴 pytest 门禁 + G-Eval rubric |
| **garak** | 3 | 5 | 3 | 2 | 3 | **3.25** | 红队语料来源（非核心裁决器） |
| **Langfuse** | 3 | 3 | 3 | 3 | 3 | **3.00** | 若要看板则首选（运维较重） |
| **Ragas** | 2 | 4 | 3 | 3 | 2 | **2.80** | 只借鉴 faithfulness 思想 |
| **LangSmith** | 3 | 3 | 3 | 2 | 2 | **2.75** | 不建议（闭源 + 非你的栈） |
| **PyRIT** | 2 | 3 | 3 | 2 | 2 | **2.45** | 当前暂不碰 |
| **OpenAI Evals** | 2 | 3 | 2 | 1 | 1 | **1.95** | 弃用，不碰 |
| **WebArena/OSWorld/GAIA** | 2 | 1 | 2 | 1 | 1 | **1.55** | 暂不碰（太重 + 已被证可 hack） |

### 6.2 能力 ROI（对 AgentEval-Lite 自身该建/该强化的能力）

| 能力 | 现状 | Impact | Cost | Risk↓ | Reuse | Fit | **ROI** |
| --- | --- | :--: | :--: | :--: | :--: | :--: | :--: |
| 深化红队 suite（注入/越权/exfil/外科偷看回归） | ✅已落地(14项13D) | 5 | 4 | 5 | 4 | 5 | **4.65** |
| CI smoke eval 打包（GitHub Actions/门禁模板） | ✅已落地 | 4 | 5 | 4 | 4 | 5 | **4.30** |
| 任务集 suite 批跑 + 结果汇总 | ✅已落地 | 4 | 4 | 3 | 4 | 5 | **3.95** |
| 多 Agent 对比报告 | ✅已落地 | 4 | 4 | 3 | 3 | 4 | **3.75** |
| PASS_TO_PASS 回归门（借 SWE-bench） | ✅已落地 | 4 | 4 | 4 | 3 | 3 | **3.75** |
| 终态比对判分（借 tau-bench） | ✅已落地(world_state) | 4 | 3 | 4 | 3 | 3 | **3.55** |
| OpenInference trace 导出适配器 | ✅已落地(export) | 3 | 4 | 3 | 4 | 3 | **3.35** |
| LLM judge rubric（低权重维度） | 缺（等判分模型限流解决） | 3 | 3 | 3 | 3 | 3 | **3.00** |
| cost/latency 采集 | ✅已落地(自报口径) | 3 | 4 | 2 | 3 | 3 | **3.00** |
| Dashboard（自建） | 缺（已可外接 Phoenix 等） | 3 | 2 | 2 | 2 | 3 | **2.45** |
| Docker sandbox（根治外科偷看） | 缺(Phase4) | 4 | 2 | 5 | 2 | 2 | **3.25** |

---

## 7. A. 立刻做（高 ROI）

> 前提修正：**submission schema 校验 / trace JSONL / hidden judge / tool-call trace 核验 / 非法提交拒绝 / markdown+JSON 报告——你已经做完了**（见 §11 差距矩阵"已具备"行）。所以"立刻做"聚焦真实缺口。
>
> **落地状态（已全部完成并测试验证）**：A 类四项高 ROI 行动均已实现——红队套件从 8 项扩到 14 项（13 DEFENDED / 1 登记基线残留），`bin/ci-smoke.sh` 三道闸端到端跑通；两条门禁的失败退出码（suite=3、redteam=1）已用负向用例验证有效；红队门禁判定逻辑随后抽为 `redteam/gate_lib.sh` 纯函数，配 `redteam/test_gate.sh` 正/负向自测（7 用例，含 `RT_ALLOWED_VULN=0`、INFRA、CHECK 强制失败）并进 CI，防止 fail-closed 契约未来退化。B 类行动 5（多 Agent 对比 + pass^k）、行动 6（终态比对）、行动 7（OpenInference 导出）、行动 9（cost/latency）也已落地（见 §8）；`mvn test` 现为 127 个用例全绿。详见各行动下的「✅ 已落地」条目。B 类仅剩行动 8（LLM judge，等判分模型 429 限流解决）。

### 高 ROI 行动 1：深化红队 suite（注入/越权/exfil/外科偷看回归）

- 具体动作：在 `redteam/` 下把当前 7 类扩到 ~15 类，新增：间接提示注入（把注入藏进 work/ 材料让 Agent 读到）、越权工具调用（调 allowlist 外的工具应被网关拒绝并留痕）、data exfiltration via tool（把 hidden 内容当工具入参外传）、外科式偷看回归（固化当前 VULNERABLE 项为"已知红线用例"，防止未来悄悄劣化）。用 garak 的 promptinject 文本作注入语料源。
- 预期收益：把安全边界从"挡住常见作弊"推进到"挡住系统性红队"，直接降低你 README 承认的两处残余风险的回归概率。
- 实施成本：3-5 天（复用现有 `run_all.sh` 判定矩阵框架）。
- 验证方式：`bash redteam/run_all.sh` 从 8 项扩到 ~15 项，新增项预期全部 DEFENDED（外科偷看类标注为"已知残余，Phase 4 容器根治"，锁定不劣化）。
- ROI：**4.65 / 5**。
- 不做的风险：安全是你唯一"诚实告知有洞"的地方；不深化红队，改一版 judge/tool 就可能悄悄退化，且无回归网兜住。
- ✅ 已落地：红队从 8 项扩到 13 项，新增 **G 越权工具调用**（`redteam/G-tool/unauthorized_tool_agent.sh`，网关按 allowlist 拒绝 `tool_not_allowed`）、**H 借工具入参夹带 canary 外泄**（`redteam/G-tool/exfil_tool_arg_agent.sh`，靠新扩展的 traces/ 扫描检出）、**D2 PASS_TO_PASS 回归**（见行动 4）；`no_canary_leak` 扫描面扩到 `traces/`（`RulesJudge`，配 2 个正/反单测 `RulesJudgeHardeningTest`）；外科式偷看（A）固化为登记基线残留，`run_all.sh` 新增 `RT_ALLOWED_VULN`（默认 1）门禁，超基线即退出 1。矩阵：12 DEFENDED / 1 残留（本条为行动落地时点数据；行动 6 新增红队 I 后，当前为 14 项 / 13 DEFENDED / 1 残留）。

### 高 ROI 行动 2：打包 CI smoke eval（门禁模板）

- 具体动作：加 `.github/workflows/ci.yml` 或 `bin/agent-eval ci-smoke`，跑全部 scripted replay + `redteam/run_all.sh`，用你已有的退出码（3=未过线门禁）做 PR 门禁；输出 JSON 汇总。
- 预期收益：任何人改 judge/schema/tool 后，CI 自动跑回归 + 红队，退化立刻挡在 PR。
- 实施成本：0.5-1 天（退出码 + scripted replay 都已就绪，只差编排）。
- 验证方式：故意改坏一条 rule，CI 应红；恢复后应绿。
- ROI：**4.30 / 5**。
- 不做的风险：现在回归靠人手跑 `mvn test` + replay，改动多了必漏。
- ✅ 已落地：新增 `.github/workflows/ci.yml` 与 `bin/ci-smoke.sh`（同口径三道闸：`mvn package` → `suite --fail-on-not-passed` → `redteam/run_all.sh`，另含 `validate` 全任务体检）；负向验证——把某任务 replay 改成只交失败提交，`suite --fail-on-not-passed` 退出 3；`RT_ALLOWED_VULN=0` 时红队门禁退出 1；默认基线下 `bin/ci-smoke.sh` 端到端退出 0。

### 高 ROI 行动 3：任务集 suite 批跑 + 汇总报告

- 具体动作：加 `bin/agent-eval run-suite --tasks tasks/ --agent ...`，串跑多任务，产出 `suite_report.{json,md}`（每任务通过/分数/耗时 + 汇总）。
- 预期收益：从"单任务评测"升级到"能力面板"，是内部试用的最小前置。
- 实施成本：2-3 天（RunManager 已能跑单任务，加编排层 + 聚合）。
- 验证方式：对 5 个内置任务批跑，汇总数字与逐个单跑一致。
- ROI：**3.95 / 5**。
- 不做的风险：无法一次性回答"这个 Agent 在我全部任务上表现如何"。
- ✅ 已落地：新增 `agent-eval suite` 子命令（`SuiteCommand`）+ 编排层 `SuiteRunner`，扫描任务库中具备 `samples/replay.yaml` 的任务串跑并产出 `suite_report.{json,md}`（逐任务状态/分数/耗时 + 汇总 + `all_passed` 判据），支持 `--tasks` 过滤与 `--fail-on-not-passed`；集成测试 `SuiteRunnerTest`（3 个：全量汇总一致、报告结构、过滤）全绿。对 5 个内置任务批跑均 PASSED，汇总数字与逐个单跑一致。

### 高 ROI 行动 4：借 SWE-bench 的 PASS_TO_PASS 回归门强化 code-fix 判分

- 具体动作：`code-fix-001` 的 command check 现在跑"修没修对"（FAIL_TO_PASS 语义），补一组"原本就该过的测试仍要过"（PASS_TO_PASS），改对但弄坏别处即判负。
- 预期收益：封杀"为过测试而破坏其他功能"的作弊/劣解。
- 实施成本：1 天（隐藏 harness 加一组基线测试）。
- 验证方式：构造一个"删掉报错分支让目标测试过、但破坏另一函数"的提交，应判负。
- ROI：**3.75 / 5**。
- 不做的风险：code_fix 类任务可能给"治标不治本"的解满分。
- ✅ 已落地：`code-fix-001` 隐藏行为规格 `CalculatorSpec.java` 拆成 FAIL_TO_PASS（`total` 越界+null 修复）与 PASS_TO_PASS（既有 `maxPrice` 行为不得改坏）两组断言；工作区与 fixed 样例同步补入 `maxPrice`，任务 brief/README 明示"不得破坏既有方法"；红队 D2（`redteam/D-command/regress_agent.sh`：修好 total 却把 maxPrice 改成恒 0）被 SPEC_BEHAVIOR 一票否决判负 → DEFENDED；既有端到端用例仍全绿（fixed 样例 100 分通过）。

---

## 8. B. 第二阶段做（中 ROI）

**行动 5：多 Agent 对比报告** — ROI 3.75；动作：suite 批跑基础上加"同任务多 Agent 并列 + pass^k 可靠性列（借 tau-bench）"；成本 3-5 天；验证：manual/scripted/cli 三 Agent 同任务对比表；不做风险：无法横向选型。

> ✅ 已落地：`SuiteRunner` 重构为「AgentSpec 工厂 + repeat」模型——`suite` 不再只跑回放：`--agent cli --cmd '...'` 可驱动真实命令行 Agent 批量过全部任务；`--repeat k` 按 **pass^k** 口径（k 次全过才算稳定通过，借 tau-bench）判可靠性；`--agents-file agents.yaml` 让多个 Agent（scripted/cli 混搭）并列跑同一任务集，产出「任务 × Agent」矩阵 + 按稳定通过数排序的排名面板（`suite_report.{json,md}`，逐 run 记录墙钟时延并聚合，即行动 9 的 latency 半边；token/cost 需 Agent 侧上报，未内置）。CI 门禁判据同步升级为「全部任务稳定通过」。测试：`SuiteRunnerTest` 新增 3 例（真实 cli Agent 真进程 repeat=2 全过、不稳定 Agent「首跑过复跑挂」被 pass^2 抓出、scripted vs cli 对比报告落盘）+ `--agents-file` 解析回归 5 例，`mvn test` 93 例全绿（本条为行动落地时点数据；后续行动全部落地后，当前为 127 例）；实跑验证：demo cli Agent repeat=3 全过（pass^3=true，平均 88ms/次），双 Agent 对比面板正确呈现 `tool-call-001` 上 cli 挂、scripted 过的差异。

**行动 6：终态比对判分（借 tau-bench）** — ROI 3.55；动作：为 api_payload/tool_call 类任务加"最终世界状态 vs 目标状态"比对（不只比字段）；成本 3-4 天；验证：对话/多步后终态一致才算过；不做风险：Agent 可能"过程对、终态错"仍得分。

> ✅ 已落地：新增第 14 种 check `world_state`（`RulesJudge`）——把 trace 中**签名可核验且成功**的写工具调用按 `{tool, input}` 折叠成世界终态，与隐藏期望终态比对（支持 `scope: attempt/run` 与 `order_sensitive`，多重集比较处理重复写入）；评的是「实际改成了什么」而非「提交里说了什么」，且事实源是防伪 trace 而非可篡改的状态文件。`tool-call-001` 升级为「查等级 + 真开卡」双工具任务：新增 `card.create` 写工具与 `FINAL_WORLD_STATE`（blocking）规则，replay 编排三轮闭环（编造 call_id 被识破 → 真实调用但开错卡被终态比对拦下 → 全对通过）。红队 I（`redteam/I-endstate/wrong_endstate_agent.sh`：流程全对、提交也「说」开了 PLATINUM，实际却开成 STANDARD——旧检查全过）被 `FINAL_WORLD_STATE` 一票否决 → DEFENDED。测试：`RulesJudgeWorldStateTest` 11 例（含伪造事件不计入、失败调用不计入、attempt/run 两种 scope、顺序敏感、多重集语义、配置校验）+ 端到端回放断言全绿。
>
> ✅ 行动 7 已落地：新增 `agent-eval export` 子命令（`OtlpTraceExporter`）——把 run 的 trace.jsonl 纯读转换为标准 OTLP/JSON + OpenInference 语义（run→AGENT / attempt→CHAIN / tool_call→TOOL 三层 span，其余事件为 span event；判分 score/passed 冒泡为 attempt span 属性可直接在看板过滤）。trace/span id 由 run/attempt/call id 的 SHA-256 确定性派生，幂等重推不产生分叉数据。`--endpoint` 直推 OTLP/JSON 收集器；Phoenix 17.x 的 `/v1/traces` 只收 protobuf（415），用 `research/poc/phoenix/push_otlp_json.py` 转 protobuf 推送——**已实测本机 Phoenix 完整收到 8-span 三层树**（GraphQL 核对 span kind/层级无缺）。该脚本还用 proto3 `json_format.Parse` 严格校验导出 JSON 合规（未知字段即拒）。测试：`OtlpTraceExporterTest` 3 例（三层结构与父子引用、字节级幂等、本地 HTTP 收集器实收）全绿。

**行动 8：LLM judge rubric（低权重维度）** — ROI 3.00；动作：为 doc-analysis/prd-review 类主观任务加一个低权重（≤20%）LLM judge 维度，借 DeepEval G-Eval / promptfoo llm-rubric 的准则写法；成本 3-5 天（需稳定判分模型）；验证：与人工基线相关性达 0.7 以上；不做风险：主观质量维度只能靠关键点覆盖率近似。注：依赖稳定 LLM，当前 429 限流下需先解决判分模型可用性。**（未落地：等判分模型限流解决，B 类唯一余项）**

**行动 9：cost/latency 采集** — ROI 3.00；动作：trace 事件补 tokens/latency/cost 字段，报告聚合；成本 1-2 天；验证：报告出现每 attempt 耗时/成本；不做风险：无法做性价比对比。

> ✅ 已落地：latency 半边此前随行动 5 完成（suite 逐 run 墙钟时延 + 聚合）；本轮补齐 cost/token 半边——提交信封新增**可选** `usage` 对象（`model/input_tokens/output_tokens/cost_usd`，schema 强校验，非法值整份提交被拒），Agent 自报后框架记 `usage_recorded` 签名 trace 事件留痕；`report.json` 聚合出 `cost` 节点、`report.md` 渲染「成本（Agent 自报，不参与评分）」段；suite/多 Agent 对比面板聚合出总成本列（未上报则留空不误标）。口径诚实声明：成本是 Agent 自报值，仅作 ROI 参考不参与评分，防伪边界靠签名事件（防事后篡改，不防 Agent 谎报）。测试：`UsageReportingTest` 3 例 + `SuiteRunnerTest` 聚合 2 例全绿。

---

## 9. C. 暂时不做（低 ROI 或过重）

**Docker sandbox（Phase 4）** — ROI 3.25 但成本高：为什么现在不做——你已诚实标注"外科式偷看"需容器根治，但当前阶段 Agent 多为协作/受控内部场景，投入产出比不划算；未来条件——当要对接**公开/强对抗**评估（外部不可信 Agent）时必须做；替代方案——先用红队回归把该风险"锁定为已知红线"（行动 1）。

**自建 Dashboard** — ROI 2.45：为什么不做——自建看板成本高且重复造轮子；未来条件——内部试用规模化、需要非工程人员看结果时；替代方案——OpenInference 导出到 Phoenix/Langfuse（行动 7）白嫖成熟看板。

**完整 SForge/SWE-bench 复刻** — 为什么不做——双容器 + K8s + 长时程守护是重工程，与"轻量"定位冲突；未来条件——团队扩张 + 需要 12h+ 长时程学习曲线测量时；替代方案——只借鉴 stop-hook/auto-eval/best-of-N 思想（你已实现前两者）。

**PyRIT / OpenAI Evals / 重环境 benchmark（WebArena/OSWorld/GAIA）** — 为什么不做——PyRIT 比 garak 重且不对口；OpenAI Evals 正在关停；重环境 benchmark 太重且 2026-04 已被证可 reward-hack；替代方案——garak 作红队语料、promptfoo 作 prompt 回归。

**企业级权限系统 / 完整防篡改审计** — 为什么不做——当前指纹 + HMAC + 从干净目录复算已覆盖主要威胁；未来条件——多租户 SaaS 化；替代方案——保持现有轻量完整性校验。

**LangSmith 重度绑定** — 为什么不做——闭源 SaaS + 非 LangChain 栈；替代方案——OTel 中立层（Phoenix/Langfuse 可换）。

---

## 10. 成熟评估框架能力模型（Level 0-5）

### Level 0 概念阶段

- 有什么：一个"任务→提交→判分"的想法。缺什么：可执行的判分。能做什么：讨论。不能做什么：跑任何真实评估。风险：停在 PPT。进入下一阶段门槛：能跑通 1 个 end-to-end 假任务。

### Level 1 Demo 阶段

- 必备：单任务 + 硬编码判分 + 能出个分。代表参考：promptfoo 单 YAML。验证：1 个任务能跑出 pass/fail。门槛：判分与提交解耦（schema 化）。

### Level 2 MVP 阶段（成熟框架的最低"可用"线）

- 必备：TaskSpec + 结构化 submission schema 校验 + 确定性 judge + trace + JSON/MD 报告 + resume + 3-5 示例任务 + 非法提交拒绝。代表参考：Inspect AI(单机)、promptfoo。验证：scripted replay 跑通 fail→pass 闭环 + 非法提交全被拒。门槛：多任务批跑 + 多 Agent 接入。
- **→ AgentEval-Lite 当前在这里偏上（Phase 1.5），且带 HMAC 防伪 trace，超出多数 MVP。**

### Level 3 内部试用阶段

- 必备：任务集 suite 批跑 + 多 Agent 对比 + CI smoke + 回归报告 + 红队 suite + 工具权限强校验。代表参考：Inspect AI(sandbox) + promptfoo redteam。验证：CI 上每 PR 自动跑回归 + 红队。门槛：可观测（trace 看板）+ 主观维度判分。

### Level 4 接近成熟阶段

- 必备：trace 平台接入 + LLM judge rubric + cost/latency + docker sandbox + run replay + 人工复核通道。代表参考：Inspect + Phoenix/Langfuse。验证：外部/半可信 Agent 也能可信评测。门槛：规模化调度 + 数据集治理。

### Level 5 成熟规模化阶段

- 必备：分布式调度(K8s) + 长时程运行支撑 + 数据集版本治理 + 防 reward-hacking 审计 + best-of-all-submissions 曲线。代表参考：EdgeBench/SForge。验证：12h+ 长时程 + 前沿模型横评可复现。门槛：团队 + 长期投入。

---

## 11. AgentEval-Lite 能力差距矩阵（25 项）

> 状态图例：✅已具备且较硬 / 🟡部分具备 / ❌缺失。优先级：P0=立刻 P1=第二阶段 P2=暂不。

| # | 能力项 | 成熟框架通常怎么做 | AgentEval-Lite 现状 | 差距 | 优先级 | ROI | 建议 |
| --- | --- | --- | --- | :--: | :--: | :--: | --- |
| 1 | TaskSpec | YAML/py 声明任务 | ✅ `task.yaml`+维度权重校验 | 无 | — | — | 保持 |
| 2 | Work/Hidden 隔离 | 容器/约定 | ✅ workspace 私有副本 + hidden/ | 无（同机非容器） | P2 | 3.25 | Phase4 容器 |
| 3 | Submission schema | is-json/JSON schema | ✅ envelope+6 分型 draft2020-12 | 无 | — | — | 保持 |
| 4 | Judge runner | scorer/eval_cmd | ✅ 13 check + 脚本 judge | 无 | — | — | 保持 |
| 5 | LLM judge rubric | G-Eval/llm-rubric | ❌ | 有 | P1 | 3.00 | 低权重维度借鉴（等限流解决） |
| 6 | Trace log | JSONL/OTel span | ✅ JSONL16事件+**HMAC签名**+OTLP导出 | **超出** | — | — | 保持（业界少见防伪） |
| 7 | Tool call trace | messages/span | ✅ 网关+签名事件+call_id核验+world_state终态比对 | 无 | — | — | 保持 |
| 8 | Tool permission | allowlist | ✅ allowed_tools 强校验 | 无 | — | — | 保持 |
| 9 | Red team suite | garak/PyRIT/promptfoo | ✅ 14 项 13 DEFENDED + 门禁基线（行动1已落地） | 小 | done | 4.65 | 已深化，续补语料 |
| 10 | Report JSON | 平台/文件 | ✅ report.json | 无 | — | — | 保持 |
| 11 | Report Markdown | 平台 | ✅ report.md | 无 | — | — | 保持 |
| 12 | Multi-agent 对比 | 并列表/pass^k | ✅ suite 对比矩阵 + pass^k + 排名（行动5已落地） | 无 | done | 3.75 | 已并列 |
| 13 | CI integration | 退出码/pytest | ✅ ci.yml + ci-smoke.sh 三道闸（行动2已落地） | 无 | done | 4.30 | 已打包 |
| 14 | Dashboard | Phoenix/Langfuse | 🟡 不自建；`export` 已可外接 Phoenix/Langfuse（行动7已落地） | 小 | done | 3.35 | 外接非自建 |
| 15 | Cost/latency | 平台聚合 | ✅ latency 逐 run 采集聚合 + usage 自报成本聚合（行动9已落地） | 无 | done | 3.00 | 自报口径，不参与评分 |
| 16 | Regression testing | 快照/replay | ✅ scripted replay + `suite` 批跑并入 CI | 无 | — | — | 保持（已并入CI） |
| 17 | Dataset 管理 | HF/CSV/注册表 | ✅ tasks/ + `suite` 批跑汇总（行动3已落地） | 小 | done | 3.95 | 已批跑 |
| 18 | Human review | 人工标注 | 🟡 manual+needs_human_review | 小 | P1 | — | Phase3 通道化 |
| 19 | Reproducibility | docker/seed | ✅ 指纹+离线复算 | 无 | — | — | 保持（强项） |
| 20 | Sandbox 隔离 | docker/K8s | ❌ 同机同用户 | 有 | P2 | 3.25 | Phase4 |
| 21 | Prompt injection 测试 | garak/promptfoo | 🟡 E-judge/inject.json + 越权工具调用(G) | 中 | P1 | 4.65 | 续补间接注入语料 |
| 22 | Hidden exfil 测试 | — | ✅ canary 扩 traces + 工具入参夹带(H)；外科偷看锁基线 | 小 | done | 4.65 | 已封工具通道 |
| 23 | Resume/retry | checkpoint | ✅ run_state+--resume | 无 | — | — | 保持 |
| 24 | Auto-eval | 后台守护采样 | 🟡 config 有，编排待确认 | 小 | P1 | — | Phase3 |
| 25 | Stop hook | 拦截提前退出 | ✅ stop_hook_triggered | 无 | — | — | 保持 |

**矩阵结论（随行动 1-7/9 落地更新）**：25 项里 **17 项已具备（其中 trace 防伪 + 终态比对 + OTLP 导出组合超出业界常规）**，5 项部分具备，3 项缺失。当初的 3 类 P0（红队深化、CI 打包、suite 批跑）与次批的终态比对/可观测/成本采集均已闭环；剩余缺口只有 **LLM judge(5，等限流解决)、自建 Dashboard(14，按定位不做只外接)、Docker sandbox(20，Phase 4)**。

---

## 12. 推荐架构路线图

### 0-2 周（把已达标的 MVP 补成"可回归的 MVP"）

- ✅ 已有：任务格式 / submission schema / judge runner / trace JSONL / JSON+MD 报告 / 5 示例任务 / 非法提交拒绝。
- 🔨 本阶段做：**CI smoke 打包（行动2）** + **红队 suite 深化 v1（行动1，注入+越权+exfil）** + **PASS_TO_PASS 回归门（行动4）**。
- 产出：改任何 judge/schema，CI 自动跑回归 + 红队并门禁。

### 2-6 周（进入内部试用 = Level 3）✅ 已完成

- **任务集 suite 批跑（行动3）** + **多 Agent 对比 + pass^k（行动5）** + **终态比对判分（行动6，`world_state`）** + allowed_tools 强校验回归（红队 G）。
- 产出：能一次性回答"Agent X 在我全部任务上 vs Agent Y"。✅

### 6-12 周（接近成熟 = Level 4）🟡 大半完成

- ✅ **OpenInference trace 导出（行动7）→ 外接 Phoenix/Langfuse 看板**（Phoenix 实测端到端）+ ✅ **cost/latency（行动9，自报口径）**；
- ⏳ 余项：**LLM judge rubric 低权重维度（行动8，等判分模型限流解决）** + **Docker sandbox（Phase4，根治外科偷看）** + run replay（已有 judge 复算，补 UI）+ 人工复核通道。

**暂不建议（现在做即负 ROI）**：自建 dashboard、完整 SForge/SWE-bench 复刻、PyRIT、重环境 benchmark、企业权限系统、LangSmith 重绑定。

---

## 13. 立即可执行的 10 个行动项（状态更新）

1. ✅ **CI smoke 打包**（ROI 4.30）：`.github/workflows/ci.yml` + `bin/ci-smoke.sh` 三道闸已落地。
2. ⏳ **红队注入用例**（并入 ROI 4.65）：直接注入（E）已覆盖；garak promptinject 语料造**间接注入**任务仍可续补。
3. ✅ **红队越权用例**：红队 G，网关拒绝 + 留痕，判负。
4. ✅ **红队 exfil 用例**：红队 H，canary 扫描扩到 traces/ 后命中。
5. ✅ **外科偷看回归锁定**：红队 A 固化为登记基线（`RT_ALLOWED_VULN=1`），超基线即门禁失败。
6. ✅ **PASS_TO_PASS 回归门**（ROI 3.75）：code-fix-001 双组断言，红队 D2 DEFENDED。
7. ✅ **suite 批跑 CLI**（ROI 3.95）：`agent-eval suite` + `suite_report.{json,md}`。
8. ✅ **多 Agent 对比列**（ROI 3.75）：任务×Agent 矩阵 + pass^k + 排名面板。
9. ✅ **OpenInference 导出适配器**（ROI 3.35）：`agent-eval export`，Phoenix 实测端到端。
10. ⏳ **判分模型可用性**：解决 88code 中转 429 限流（配额/换 provider），为行动 8 的 LLM judge 铺路——**当前唯一阻塞项**。

---

## 14-17. 自研 / 集成 / 借鉴思想 / 不做

### 应该自研（你的护城河，别外包）

- Hidden judge + work/hidden 文件隔离；HMAC 防伪 trace + 判分只认可核验事件；canary 泄露探针；指纹可复现判分；结构化 submission 契约。**理由**：这些正是成熟框架**普遍缺失**的（promptfoo/Inspect 无防伪 trace，无强隔离），是 AgentEval-Lite 的差异化价值。

### 应该集成（可选外挂，别重造）

- promptfoo（prompt 层回归 + redteam 语料生成，用你的 cli adapter 挂）；garak（红队注入语料来源）；Phoenix/Langfuse（trace 看板，经 OpenInference 导出）。**理由**：这些是成熟且免费/开源的，自造不划算。

### 只借鉴思想（抄设计不抄代码）

- Inspect AI：Solver/Scorer 分离、scorer 核验 tool_call、epochs、sandbox 分级。
- tau-bench：DB 终态比对、pass^k 可靠性度量。
- SWE-bench：FAIL_TO_PASS/PASS_TO_PASS 双门、docker 可复现、reward-hacking 威胁模型。
- DeepEval/promptfoo：断言分类法（确定性 vs LLM-rubric）、pytest 门禁。
- EdgeBench/SForge：stop-hook、auto-eval、best-of-all-submissions（前两者你已实现）。

### 不要做（现在做即浪费）

- OpenAI Evals（弃用）；PyRIT（重且不对口）；WebArena/OSWorld/GAIA 复刻（重 + 可 hack）；自建 dashboard；K8s 大规模；完整 SForge 复刻；LangSmith 重绑定；企业权限系统。

---

## 18. 结论：你当前最该优先投入什么

**一句话**：地基已超出 MVP，别再修地基；把下一笔投入压在"红队深化 + CI/suite/多 Agent 打包"这三件 P0 上。

排序（按 ROI × 紧迫性）：

1. **红队 suite 深化（ROI 4.65）**——这是你唯一"诚实承认有洞"的地方，且没有回归网。补注入/越权/exfil 用例 + 锁定外科偷看红线。**这是最高优先级。**
2. **CI smoke 打包（ROI 4.30）**——你所有材料（退出码/replay/redteam 脚本）都就绪，0.5-1 天就能把"改动即回归"闭环，性价比最高。
3. **suite 批跑 + 多 Agent 对比（ROI 3.95/3.75）**——从"单任务评测器"跨到"能力面板"，是内部试用的门槛。

**trace 平台、LLM judge、docker sandbox 都往后放**：前两者用"借鉴 schema + 可选外挂"低成本接，最后一个等你要对接不可信外部 Agent 时再做。**不要现在追求大而全平台**——你的差异化在"防伪判分"，不在"看板漂亮"。

> **结论落地进度（截至 2026-07-07）**：上述三件 P0 与次批的终态比对（行动6）、trace 外接看板（行动7）、cost/latency（行动9）均已落地并进 CI；红队 14 项（13 DEFENDED / 1 登记基线），`mvn test` 127 例全绿。另：路线图 Phase 2 已同日落地——HTTP AgentAdapter 窄口径（框架推 instructions、收 submission 的 `AgentAdapter` HTTP 实现，`run`/`suite`/`agents-file` 全线支持；刻意不含 §9 已列为暂不做的 Playwright 级重环境链路）+ `task init` 任务脚手架 + `validate` 规则深度 lint。余项只有两个：LLM judge（行动8，阻塞在判分模型 429 限流）与 Docker sandbox（Phase 4，等对接不可信外部 Agent 时做）；真实工具接入（Phase 3）按产品定位决策——当前 mock 保确定性优先。

---

## 附录：PoC 复现命令

```bash
cd research/poc
# 环境（一次性）
python3.13 -m venv .venv && .venv/bin/pip install inspect-ai garak arize-phoenix \
  openinference-semantic-conventions opentelemetry-sdk opentelemetry-exporter-otlp
npm install --registry=https://registry.npmjs.org/ promptfoo

# PoC A：Inspect 工具调用纪律（离线 mockllm）
.venv/bin/python inspect_toolcall_eval.py
# 期望：honest=1.0, cheating=0.0

# PoC B：promptfoo prompt 回归 + CI 门禁（离线）
cd promptfoo && ../node_modules/.bin/promptfoo eval -c promptfooconfig.yaml --no-cache; echo "exit=$?"
# 期望：2 pass 1 fail，exit=100

# PoC C：garak 红队探针（离线镜像生成器）
.venv/bin/python -m garak --model_type test.Repeat --probes promptinject.HijackHateHumans --generations 2
# 期望：promptinject 探针跑完，报告 JSONL 生成

# PoC D：Phoenix/OTel trace + tool-call 断言
.venv/bin/python -m phoenix.server.main serve &   # 起在 6006
.venv/bin/python phoenix_trace_eval.py
# 期望：user.lookup 断言=True，payment.charge=False，POST /v1/traces→200

# PoC D2（行动7 落地后）：真实 run 的 trace 导出并推给 Phoenix
cd .. && bin/agent-eval run --task tasks/tool-call-001 --agent scripted \
  --script tasks/tool-call-001/samples/replay.yaml --runs-root runs/phoenix-demo
RUN_DIR=$(ls -dt runs/phoenix-demo/tool-call-001/run_* | head -1)
bin/agent-eval export --run "$RUN_DIR"            # 产出 report/trace.otlp.json（OTLP/JSON）
research/poc/.venv/bin/python research/poc/phoenix/push_otlp_json.py \
  "$RUN_DIR/report/trace.otlp.json"               # 转 protobuf 推 Phoenix（17.x 只收 protobuf）
# 期望：JSON 校验通过 8 span、HTTP 200；Phoenix 看板出现 AGENT/CHAIN/TOOL 三层 trace
```
