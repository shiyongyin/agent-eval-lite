#!/usr/bin/env bash
# 红队门禁判定逻辑（从 run_all.sh 抽出为可独立测试的纯函数）。
# 抽出动机：门禁本身此前没有回归保护，未来改动可能悄悄把 fail-closed 退化成
# fail-open；纯函数 + redteam/test_gate.sh 的毫秒级正/负向自测补上这层网。
#
# 注意：变量后紧跟全角字符时必须用 ${VAR}，macOS bash 3.2 会把多字节字符并入变量名。
#
# evaluate_gate <vulnerable> <infra> <check> <allowed_vuln>
#   stdout : 每行一个门禁失败原因（通过则无输出）
#   退出码 : 0=门禁通过, 1=门禁失败
evaluate_gate() {
  local vuln="$1" infra="$2" checkn="$3" allowed="$4"
  local gate=0
  if [ "$vuln" -gt "$allowed" ]; then
    echo "VULNERABLE=${vuln} 超出允许基线 ${allowed}（出现新的可绕过点）"
    gate=1
  fi
  if [ "$infra" -gt 0 ]; then
    echo "INFRA=${infra}（基础设施失败不猜好坏，按门禁失败处理）"
    gate=1
  fi
  if [ "$checkn" -gt 0 ]; then
    echo "CHECK=${checkn}（观测值偏离登记预期，需人工确认后更新基线）"
    gate=1
  fi
  return "$gate"
}
