#!/usr/bin/env bash
# 红队门禁判定的正/负向自测（毫秒级，不重跑攻击用例）。
# 门禁契约（fail-closed）：
#   1) VULNERABLE 超出登记基线 → 失败；基线内 → 放行
#   2) INFRA > 0 → 失败（基础设施故障不猜好坏）
#   3) CHECK > 0 → 失败（观测值偏离登记预期）
# 本自测钉死上述契约，防止未来改动 run_all.sh / gate_lib.sh 时退化成 fail-open。
#
# 用法： bash redteam/test_gate.sh
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
source ./gate_lib.sh

FAILED=0

expect() { # 用例名 期望退出码 vuln infra check allowed
  local name="$1" want="$2"
  shift 2
  local got=0 out
  out="$(evaluate_gate "$@")" || got=1
  if [ "$got" != "$want" ]; then
    echo "  FAIL ${name}（期望 exit=${want}，实际 exit=${got}）"
    FAILED=1
    return
  fi
  # 原因文本是 CI 排障契约的一部分：失败必须给出原因，通过必须无输出。
  if [ "$want" = "1" ] && [ -z "$out" ]; then
    echo "  FAIL ${name}（门禁失败但未输出原因）"
    FAILED=1
    return
  fi
  if [ "$want" = "0" ] && [ -n "$out" ]; then
    echo "  FAIL ${name}（门禁通过却输出了原因: ${out}）"
    FAILED=1
    return
  fi
  echo "  ok   ${name}"
}

echo "[gate-selftest] 正向：基线内应放行"
#      用例名                          期望 vuln infra check allowed
expect "全零且基线为0"                  0    0    0     0     0
expect "VULN=1 恰在默认基线1内"         0    1    0     0     1

echo "[gate-selftest] 负向：任一 fail-closed 条件应失败"
expect "VULN=2 超出基线1"               1    2    0     0     1
expect "基线收紧为0时残留VULN=1"        1    1    0     0     0
expect "INFRA=1 即失败"                 1    0    1     0     1
expect "CHECK=1 即失败"                 1    0    0     1     1
expect "INFRA+CHECK 叠加失败"           1    1    2     3     1

if [ "$FAILED" != "0" ]; then
  echo "[gate-selftest] 门禁判定自测存在失败用例" >&2
  exit 1
fi
echo "[gate-selftest] 门禁判定自测 7/7 通过（fail-closed 契约未退化）"
