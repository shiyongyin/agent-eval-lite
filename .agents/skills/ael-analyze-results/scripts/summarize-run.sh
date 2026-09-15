#!/usr/bin/env bash
# 单 run 只读摘要：status / 轨迹 / 失败 rule / 维度失分。
# 用法: summarize-run.sh <run-dir>
# 依赖: jq
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "用法: $0 <run-dir>" >&2
  exit 1
fi

RUN_DIR="${1%/}"
REPORT="$RUN_DIR/report/report.json"
if [[ ! -f "$REPORT" ]]; then
  echo "找不到 report.json: $REPORT" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "需要 jq；可读 $RUN_DIR/report/report.md 兜底" >&2
  exit 1
fi

echo "=== run 定性 ==="
jq -r '
  [
    .run.run_id,
    .run.task_id,
    .run.agent,
    .run.status,
    (.run.status_reason // ""),
    (.failure_stats.invalid_submissions|tostring)
  ] | @tsv
' "$REPORT" | awk -F'\t' '{
  printf "run_id=%s task=%s agent=%s\nstatus=%s reason=%s invalid_submissions=%s\n", $1,$2,$3,$4,$5,$6
}'

echo
echo "=== score_trajectory / attempts ==="
jq -r '
  "trajectory: " + (.score_trajectory|tostring),
  (.attempts[] | [.attempt_id, (.valid|tostring), (.score//"—"|tostring), (.failed_rule_ids|join(","))] | @tsv)
' "$REPORT"

echo
echo "=== best_attempt 失分 ==="
jq -r '
  .best_attempt as $b |
  if $b == null then "（无 best_attempt）"
  else
    "attempt=" + $b.attempt_id + " score=" + ($b.score|tostring) + "/" + ($b.max_score|tostring) + " passed=" + ($b.passed|tostring),
    ($b.dimension_breakdown[]? | select(.earned < .max) | "  dim " + .dimension + ": " + (.earned|tostring) + "/" + (.max|tostring)),
    ($b.failed_rules[]? | "  rule " + .rule_id + " [-" + (.points_lost|tostring) + "] " + .message)
  end
' "$REPORT"

echo
echo "=== failure_stats.by_rule ==="
jq -c '.failure_stats.by_rule // {}' "$REPORT"

BEST=$(jq -r '.best_attempt.attempt_id // .attempts[0].attempt_id // empty' "$REPORT")
if [[ -n "$BEST" && -f "$RUN_DIR/judge/${BEST}.judge.json" ]]; then
  echo
  echo "=== private_notes ($BEST) — 仅本地诊断，勿回喂 Agent ==="
  jq -r '.private_notes // empty' "$RUN_DIR/judge/${BEST}.judge.json"
fi

FEED="$RUN_DIR/feedback/${BEST}.feedback.json"
if [[ -n "$BEST" && -f "$FEED" ]]; then
  echo
  echo "=== feedback ($BEST) ==="
  jq '{valid, feedback, schema_errors, failed_checks, dimension_scores, next_step}' "$FEED"
fi

echo
echo "=== safety / tool_usage / cost ==="
jq '{
  safety,
  tool_usage: (.tool_usage // {}) | {
    unreferenced_success_calls,
    untrusted_trace_events,
    signature_verification,
    failed_calls
  },
  cost: (.cost // {}) | {reported, total_tokens, cost_usd}
}' "$REPORT"
