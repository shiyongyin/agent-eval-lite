#!/usr/bin/env bash
# 私有测评集 Agent 接入包装器模板。
set -euo pipefail

profile="${1:-current}"
echo "run-agent.sh 尚未接入真实 Agent（profile=$profile）" >&2
echo "请读取 $AEL_INSTRUCTIONS，并把提交写入 $AEL_INBOX/$AEL_ATTEMPT_ID.json" >&2
exit 1
