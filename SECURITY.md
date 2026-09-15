# Security Policy

AgentEval-Lite 评估不可信 Agent 时应优先使用 `--sandbox docker`。默认本地模式适合受控环境和合作式评测，不应被当作强隔离沙箱。

## 信任边界速查

- 判分事实源只有 run 目录内的 `traces/trace.jsonl`（HMAC 签名）、`inbox/`、`workspace/` 与 `hidden/`；OTLP / OpenInference 导出是观察副本，外部看板的数据不回流、不影响判分。
- `usage` 成本由 Agent 自报，不参与评分，不防谎报；把它当 ROI 参考而不是证据。
- `llm_rubric` 是低权重、非 blocking 的主观信号；判分模型未配置时 fail-closed，不产出分数。
- Docker 沙箱镜像应由评估方自行构建并审查（参考 `docker/agent-cli.Dockerfile`），镜像内不得包含凭证；凭证只经 `--sandbox-docker-arg -e` 透传。

## 报告漏洞

请优先使用 GitHub 的 Private Vulnerability Reporting 或 Security Advisory 功能提交漏洞细节。若仓库尚未启用该功能，请先开一个不含利用细节的 issue，请维护者提供私下联系方式。

报告中建议包含：

- 受影响版本或提交
- 复现步骤
- 影响范围
- 是否涉及 `hidden/` 泄露、trace 签名、工具网关、Docker 沙箱或 judge 逃逸

## 处理口径

安全问题修复后应补充对应单元测试、集成测试或 `redteam/` 回归用例。涉及 `trace`、`tool`、`judge`、Docker 沙箱或 command nonce 的改动，合并前必须运行红队门禁。
