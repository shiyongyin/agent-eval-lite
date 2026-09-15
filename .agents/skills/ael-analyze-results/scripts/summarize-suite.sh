#!/usr/bin/env bash
# suite 只读摘要：自动识别 single / comparison，打印面板与下钻 run_id。
# 用法:
#   summarize-suite.sh <suite_report.json|suite-dir>
#   summarize-suite.sh <suite...> --drill <agent> <task-id>   # 打印 run_id
# 依赖: jq
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "用法: $0 <suite_report.json|suite-dir> [--drill <agent> <task-id>]" >&2
  exit 1
fi

TARGET="$1"
shift
if [[ -d "$TARGET" ]]; then
  REPORT="$TARGET/suite_report.json"
else
  REPORT="$TARGET"
fi
if [[ ! -f "$REPORT" ]]; then
  echo "找不到 suite_report.json: $REPORT" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "需要 jq；可读同级 suite_report.md 兜底" >&2
  exit 1
fi

MODE=$(jq -r '.mode // "unknown"' "$REPORT")
SUITE_DIR=$(cd "$(dirname "$REPORT")" && pwd)
RUNS_ROOT=$(cd "$SUITE_DIR/../.." && pwd)

if [[ "${1:-}" == "--drill" ]]; then
  AGENT="${2:?需要 --drill <agent> <task-id>}"
  TASK="${3:?需要 --drill <agent> <task-id>}"
  if [[ "$MODE" == "comparison" ]]; then
    RUN_ID=$(jq -r --arg a "$AGENT" --arg t "$TASK" '
      .agents[]|select(.agent==$a)|.results[]|select(.task_id==$t)|.runs[0].run_id // empty
    ' "$REPORT")
  else
    # single 模式没有多 agent；忽略 agent 标签，按 task 取
    RUN_ID=$(jq -r --arg t "$TASK" '
      .results[]|select(.task_id==$t)|.runs[0].run_id // empty
    ' "$REPORT")
  fi
  if [[ -z "$RUN_ID" ]]; then
    echo "未找到 run: mode=$MODE agent=$AGENT task=$TASK" >&2
    exit 1
  fi
  echo "$RUNS_ROOT/$TASK/$RUN_ID"
  exit 0
fi

echo "=== suite 面板 ==="
echo "file=$REPORT"
echo "mode=$MODE"

if [[ "$MODE" == "comparison" ]]; then
  echo
  echo "agent 汇总:"
  jq -r '.agents[] | [.agent, (.passed|tostring)+"/"+(.total|tostring), (.pass_rate*100|tostring+"%"), (.all_passed|tostring)] | @tsv' "$REPORT" \
    | awk -F'\t' '{printf "  %-20s %s  pass_rate=%s  all_passed=%s\n", $1,$2,$3,$4}'
  echo
  echo "矩阵 (task × agent → score / pass_at_k):"
  jq -r '
    .agents as $as |
    ($as[0].results | map(.task_id)) as $tasks |
    $tasks[] as $t |
    (
      [$t] + [
        $as[] |
        (.results[] | select(.task_id==$t) |
          (if .score == null then "null" else (.score|tostring) end)
          + (if .pass_at_k then " ✓" else " ✗" end)
          + (if .error != null then " ERR" else "" end)
        )
      ] | @tsv
    )
  ' "$REPORT"
  echo
  echo "下钻: $0 $REPORT --drill <agent> <task-id>"
elif [[ "$MODE" == "single" ]]; then
  jq -r '
    [
      .suite.agent,
      (.suite.passed|tostring)+"/"+(.suite.total|tostring),
      (.suite.pass_rate*100|tostring+"%"),
      (.suite.repeat|tostring),
      (.suite.all_passed|tostring)
    ] | @tsv
  ' "$REPORT" | awk -F'\t' '{
    printf "agent=%s  passed=%s  pass_rate=%s  repeat=k=%s  all_passed=%s\n", $1,$2,$3,$4,$5
  }'
  echo
  echo "risk_summary:"
  jq '.risk_summary // empty' "$REPORT"
  echo
  echo "tasks:"
  jq -r '.results[] | [
    .task_id,
    .status,
    (if .score==null then "null" else (.score|tostring) end),
    (.pass_at_1|tostring),
    (.pass_at_k|tostring),
    (.error // "")
  ] | @tsv' "$REPORT" | awk -F'\t' '{
    printf "  %-24s %-14s score=%-6s pass@1=%s pass@k=%s %s\n", $1,$2,$3,$4,$5,$6
  }'
  echo
  echo "下钻: $0 $REPORT --drill <any-label> <task-id>"
else
  echo "未知 mode=$MODE；原始 keys:" >&2
  jq 'keys' "$REPORT" >&2
  exit 1
fi
