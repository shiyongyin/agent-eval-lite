# EdgeBench / SForge 调研总结

> 资料来源：edge-bench.org、论文（edge-bench.org/paper.pdf, 2026-07-02）、GitHub ByteDance-Seed/EdgeBench
>（README、docs/en/*、sforge 源码目录、examples/single-task-docker）。调研日期：2026-07-06。

## 1. EdgeBench 是什么

134 个真实世界任务（公开 51 个）组成的 benchmark，测量的不是「模型一次性能答对什么」，而是
**Agent 在可执行环境中依靠反馈持续学习改进的能力**。每个任务运行 12 小时以上，记录整条
改进轨迹（best-so-far 曲线），论文核心发现：性能随交互时间呈 log-sigmoid 缩放律（R²=0.998），
前沿模型的环境学习速度约每 3 个月翻倍。

## 2. SForge 是什么

EdgeBench 的评估 harness（Python 包 `sforge`，Apache-2.0）。核心是**双容器隔离 + 提交中介 +
长时程运行支撑**。组件：CLI（`sforge run/serve/build/pull/eval`）、Judge HTTP Server、
Docker/K8s 双后端、Agent 适配（claude-code/codex 子类 + factory 注册）、
log parser（structured_json/pytest_v/score_sum）、visualizer 看板。

## 3. 它解决的核心问题

1. **评测作弊（evaluation hacking）**：Agent 能看到测试就能背测试。论文附录 C 记录了真实攻击：
   靠逐 case 误差反推隐藏答案打表拿满分、过拟合固定评测 seed、篡改评分脚本。
2. **一次性提交无法测「学习」**：需要多轮提交 + 反馈 + best-of-all-submissions。
3. **长时程运行的工程问题**：Agent 提前退出、进程崩溃、上下文耗尽、API 抖动。

## 4. 与普通 Benchmark 的区别

| 普通 benchmark | EdgeBench/SForge |
|---|---|
| 静态数据集 + 一次推理 | 可执行环境 + 12h+ 持续交互 |
| 测已有知识 | 测从环境反馈中学习的能力 |
| 答案常随数据集发布 | 隐藏验收资产 + 双容器隔离 |
| 单次得分 | 全轨迹采样，best-so-far 为最终分 |
| 无过程留痕 | 提交历史、diff、对话 trace 全留痕 |

## 5. Work / Judge 容器如何隔离

- 每个任务定义两个镜像：`work.<task_id>`（Agent 可见：骨架代码、文档、本地校验工具，
  **setup_cmds 中显式删除全部测试文件**）与 `judge.<task_id>`（隐藏测试、评分脚本、expected 数据）。
- 评测时把 Agent 提交的归档复制进一个**临时（ephemeral）judge 容器**，跑 `eval_cmd`，
  只返回任务定义的结构化反馈，容器即毁。
- `submit_exclude`（默认 `["tests/"]`）防止提交物覆盖 judge 侧测试文件。
- 镜像名含 setup_cmds 内容哈希（12 位）——环境本身可复现、可校验。
- 网络隔离：Docker 后端用 host 侧 iptables 白名单（容器无 NET_ADMIN 改不了），仅放行
  judge server 与 LLM API；K8s 后端用 NetworkPolicy。

## 6. Agent 如何提交结果

- Work 容器内置 `/usr/local/bin/sforge-submit`：按 `submit_paths`/`submit_exclude` 打 tar 包 →
  POST 到 host 侧 Judge Server（`/api/v1/register` 换 token → `/api/v1/submit` 多部分上传，
  kind=agent|auto）→ 异步排队 → 轮询 `/api/v1/result/{id}`。
- 反馈内容：pass_rate、score、失败用例名（默认最多 10 个）、summary；粒度由任务设计者控制
 （只给分数=最难，给失败名=中等，给完整栈=最容易）。
- 会话 token 编码 task/run 与轮次计数（agent-1, agent-2… / auto-1, auto-2…）。
- 限流：`--max-submissions`、`--submission-cooldown`（防反馈当 oracle 用）。

## 7. Judge 如何评分

- judge 容器内跑 `eval_cmd`，输出经 parser 归一化：`structured_json`（推荐，含 valid/score/
  summary/details）、`pytest_v`、`score_sum`。
- `score_direction`: maximize|minimize；最佳提交选择策略 `selection`: `pass_rate_first` |
  `score_first` | `valid_then_score`。
- 主观任务用 LLM 评分：judge 容器内跑评分脚本调模型 API（凭证经 `SFORGE_JUDGE_EXTRA_ENV`
  注入 judge 侧，Agent 不可见）。

## 8. Auto-eval 的价值

后台守护进程按固定间隔（默认 300s）把当前工作区快照提交隐藏评测，记为 auto-N 轮次；
**结果不回注给 Agent**，只供轨迹分析。价值：即使 Agent 不主动提交，也能测到中间状态曲线；
区分「Agent 可见反馈」与「评估者专用测量」两个通道。

## 9. Stop Hook 的价值

Agent（尤其 CLI coding agent）倾向于「自认为完成」提前退出。stop hook 拦截自然退出，
告知其继续工作直到超时预算耗尽。价值：把「运行时长」变成受控实验变量，保证不同模型
在同等时间预算下比较；论文的消融显示这是长时程测量的必要机制。

## 10. Auto-Resume 的价值

异常退出（API 断连、进程崩溃、上下文限制）会绕过 stop hook。auto-resume 用 Agent 的
**原生 resume 机制**（如 `claude --resume`）带剩余时间预算重启会话，并有安全阀（退出过快
或重试过多则停）。价值：12 小时级运行的工程可靠性，避免「测的是网络稳定性不是模型能力」。

## 11. Trace Log 如何帮助复盘

- 全过程留痕：提交历史（每轮 pass_rate/score/diff）、Agent 对话输出、judge 报告、
  最终 `final_result.json`，落在 `logs/runs/<run_id>/<task_id>/`。
- visualizer 看板可回看分数轨迹、逐轮 diff、对话 trace——论文正是靠这些轨迹发现了
  「稀疏但结构化的 diagnose-edit-evaluate 循环」以及各种作弊模式（进而修任务）。
- 复盘价值：区分「真学习」与「过拟合反馈」，定位 Agent 卡在哪个瓶颈、哪轮提交带来跃升。

## 12. 适合我们借鉴的设计（保留）

1. **Work / Hidden(Judge) 物理隔离 + 提交中介**——评分永远发生在 Agent 视野之外。
2. **结构化提交契约**（等价 structured_json parser 的思想：valid/score/summary/details）。
3. **多轮提交 + 反馈闭环 + best attempt 选择**（selection 策略可配）。
4. **反馈粒度分级**（防 feedback-as-oracle）。
5. **submission 限额与冷却**（max_attempts / cooldown）。
6. **环境与规则版本指纹**（镜像内容哈希 → 我们用目录 SHA-256 指纹）。
7. **trace 全留痕 + 轨迹式报告**（含 auto-eval 采样点思想）。
8. **stop hook / auto-resume 的意图**（轻量化实现）。

## 13. 当前阶段过重、不建议照搬的设计

| 设计 | 为什么现在不做 |
|---|---|
| Docker 双镜像构建体系（BENCHMARK.yaml + 内容哈希镜像 + registry） | 我们先评「合作式」Agent，本地目录隔离够用；Docker 是 Phase 4 |
| Kubernetes 后端 / 并行集群 | 单机单任务起步，无此规模 |
| host 侧常驻 Judge HTTP Server + token 会话 + 异步排队 | 单机进程内同步调用即可；HTTP 化留到需要跨进程/跨机时 |
| iptables / NetworkPolicy 网络隔离 | Phase 4 随 Docker 一起上 |
| 12h+ 长时程 + 30min auto-eval 守护进程 | 我们的任务先以分钟~小时计；提交即评已覆盖初期需要 |
| 交互式 game mode 服务 | 与我们的任务类型无关 |
| visualizer Web 看板 | Phase 5 可选；先用 Markdown/JSON 报告 |

## 14. 轻量版必须保留的核心思想（AgentEval-Lite 的骨架）

1. Agent 只见 work，不见 hidden——目录隔离 + 运行前完整性校验 + 运行后篡改检测。
2. 提交必须是结构化 JSON，经 schema 校验才进入评分。
3. Judge 独立于 Agent 执行，输出结构化、确定性优先、带规则版本与输入指纹（可复现）。
4. 多轮提交 + 受控反馈（feedback_to_agent 字段与粒度开关）+ best attempt 选择策略。
5. 每次运行必有 JSONL trace 与 run_state（轻量 resume 的基础）。
6. 报告双格式（Markdown 给人、JSON 给机器），含逐 attempt 轨迹。
