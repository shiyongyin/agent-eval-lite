# Security Policy

AgentEval-Lite 评估不可信 Agent 时应优先使用 `--sandbox docker`。默认本地模式适合受控环境和合作式评测，不应被当作强隔离沙箱。

## 报告漏洞

请优先使用 GitHub 的 Private Vulnerability Reporting 或 Security Advisory 功能提交漏洞细节。若仓库尚未启用该功能，请先开一个不含利用细节的 issue，请维护者提供私下联系方式。

报告中建议包含：

- 受影响版本或提交
- 复现步骤
- 影响范围
- 是否涉及 `hidden/` 泄露、trace 签名、工具网关、Docker 沙箱或 judge 逃逸

## 处理口径

安全问题修复后应补充对应单元测试、集成测试或 `redteam/` 回归用例。涉及 `trace`、`tool`、`judge`、Docker 沙箱或 command nonce 的改动，合并前必须运行红队门禁。
