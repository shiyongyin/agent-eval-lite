#!/usr/bin/env bash
# AgentEval-Lite 本地 CI 冒烟门禁（与 .github/workflows/ci.yml 同口径）。
# 三道闸：单元/集成测试 → 任务集回放冒烟 → 红队回归基线。任一失败即整体失败。
#
# 用法：
#   bash bin/ci-smoke.sh            # 完整门禁（含 mvn 测试）
#   SKIP_TESTS=1 bash bin/ci-smoke.sh   # 跳过 mvn 测试，仅跑 suite + redteam（需已构建 jar）
#
# 环境变量：
#   RT_ALLOWED_VULN  红队允许的 VULNERABLE 基线（透传给 run_all.sh，默认 1）
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
CLI="./bin/agent-eval"

section() { printf '\n========== %s ==========\n' "$1"; }

if [ "${SKIP_TESTS:-0}" = "1" ]; then
  section "1/4 干净构建 CLI jar（跳过测试）"
  mvn -q -DskipTests clean package
else
  section "1/4 干净构建：测试 + Checkstyle + 覆盖率门禁"
  mvn -q -B clean verify
fi

section "2/4 任务规格静态体检"
for d in tasks/*/; do
  "$CLI" validate --task "$d"
done

section "3/4 任务集回放冒烟门禁"
"$CLI" suite --tasks-root tasks --fail-on-not-passed

section "4/4 红队回归基线门禁"
bash redteam/run_all.sh

section "冒烟门禁全部通过 ✓"
