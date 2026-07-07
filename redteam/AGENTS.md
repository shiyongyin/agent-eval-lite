# 红队套件指南（redteam/）

用真实 CLI 复现每类攻击，输出「攻击是否得逞」的判定矩阵，作为 CI 的 fail-closed 门禁。新增攻击用例的完整流程见 `docs/PLAYBOOK.md` recipe 6。

## 四态语义（结论口径，fail-closed）

| 判定 | 含义 | 对门禁的影响 |
| --- | --- | --- |
| `DEFENDED` | 框架挡住了攻击（好） | 通过 |
| `VULNERABLE` | 框架被绕过（坏） | 超出登记基线 `RT_ALLOWED_VULN` 即失败 |
| `INFRA` | 报告缺失 / JSON 解析失败等基础设施故障 | 不猜好坏，直接失败 |
| `CHECK` | 观测值偏离登记预期（如基线分数漂移） | 需人工确认，直接失败 |

## 文件角色

- `run_all.sh`：攻击编排 + 判定矩阵 + 结构化工件 `runs/redteam/redteam_report.json`。
- `gate_lib.sh`：门禁判定纯函数 `evaluate_gate`（从 run_all.sh 抽出以便自测）。注意脚本注释里的坑：macOS bash 3.2 下变量后紧跟全角字符必须写 `${VAR}`。
- `test_gate.sh`：门禁自身的正/负向自测（毫秒级），钉死 fail-closed 契约——改 gate_lib.sh 必须先过它。
- `A-hidden/ B-submission/ C-trace/ D-command/ E-judge/ G-tool/ I-endstate/ J-llmjudge/`：按攻击面分组的载荷（脚本、恶意提交、注入规则等）。`audit-report.md`：审计结论存档。
- 派生夹具不入库（见 `.gitignore`：`valid_api.json`、`B-submission/b8_oversized.json`、`E-judge/code-fix-001/`），由 run_all.sh 每次重建。

## 基线登记规则（RT_ALLOWED_VULN）

基线随 Docker 就绪度自适应：**Docker Runner 就绪 → 红队 A 在容器内演练，基线 0；不就绪 → 回退非容器 A，保留 VULNERABLE 登记基线 1**（默认模式下外科式偷看不可检测，是已知诚实声明的残余风险，见 README「安全边界」）。CI 的 `redteam-docker-enforced` job 设 `RT_REQUIRE_DOCKER=1` + `RT_ALLOWED_VULN=0`，不允许回退。

改动基线 = 改动安全承诺：必须同步 README「安全边界」、`.github/workflows/ci.yml` 注释，并在 PR 里说明理由。

## 新增攻击用例的登记路径

1. 载荷放对应攻击面目录（新攻击面则新建 `X-name/`）；
2. `run_all.sh` 加编排段：跑攻击 → 读产物 → `record "<名称>" "<期望>" "<实测>" "<判定>"`（INFRA 用 `jread`/`infra` 助手，杜绝 fail-open）；
3. 若结论改变基线，按上节同步三处；
4. 验证：`bash redteam/test_gate.sh && bash redteam/run_all.sh`，确认矩阵与 `redteam_report.json` 符合预期。

运行依赖：已构建 `target/agent-eval-lite-*-cli.jar`（缺失时 run_all.sh 会自动 `mvn -q -DskipTests package`）。
