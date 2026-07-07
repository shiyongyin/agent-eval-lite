#!/usr/bin/env bash
# 示例被评 Agent（规则版）：演示「自研 CLI Agent」如何接入 AgentEval-Lite。
# 契约：读 AEL_* 环境变量（cwd 已是 workspace），把提交信封 JSON 写到 $AEL_INBOX/$AEL_ATTEMPT_ID.json。
# 它对两类任务用真实规则逻辑作答（不是硬编码答案库）：
#   - log-triage：按「级别列 == ERROR」精确统计，并数 ERROR 行内 code= 的众数；
#   - config-audit：逐条对照基线表推导违规项，按基线声明的级别挑最严重项。
set -euo pipefail

cd "$AEL_WORKSPACE"
task_id="$(awk -F'`' '/任务 ID/{print $2; exit}' "$AEL_INSTRUCTIONS")"

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

write_submission() { # $1=answer片段(JSON)  $2=summary
    cat > "$AEL_INBOX/$AEL_ATTEMPT_ID.json" <<EOF
{
  "schema_version": 1,
  "task_id": "$task_id",
  "attempt_id": "$AEL_ATTEMPT_ID",
  "submission_type": "generic",
  "summary": "$(json_escape "$2")",
  "answer": $1,
  "known_risks": [],
  "needs_human_review": false
}
EOF
}

if [ -f service.log ]; then
    count="$(awk '$2=="ERROR"' service.log | wc -l | tr -d ' ')"
    top="$(awk '$2=="ERROR"{for(i=1;i<=NF;i++) if($i ~ /^code=/){sub(/^code=/,"",$i); print $i}}' service.log \
        | sort | uniq -c | sort -rn | awk 'NR==1{print $2}')"
    write_submission \
        "{ \"error_count\": $count, \"top_error_code\": \"$top\" }" \
        "按级别列统计 service.log：ERROR 行共 $count 条（消息文本含 error 的其他级别行不计入）；ERROR 行内最高频错误码为 $top"

elif [ -f app.yaml ]; then
    violations=()
    grep -Eq '^[[:space:]]*run_as_user:[[:space:]]*0' app.yaml && violations+=("SEC-1")
    grep -Eq '^[[:space:]]*debug:[[:space:]]*true' app.yaml && violations+=("SEC-2")
    grep -Eq '^[[:space:]]*mask_card_number:[[:space:]]*false' app.yaml && violations+=("SEC-3")
    grep -Eq '^[[:space:]]*db_credential:[[:space:]]*"' app.yaml && violations+=("SEC-4")
    if grep -Eq '^[[:space:]]*expose:[[:space:]]*public' app.yaml \
        && grep -Eq '^[[:space:]]*tls:[[:space:]]*disabled' app.yaml; then violations+=("SEC-5"); fi
    grep -Eq '^[[:space:]]*memory_limit:' app.yaml || violations+=("SEC-6")

    sorted="$(printf '%s\n' "${violations[@]}" | sort -t- -k2 -n)"
    best_rank=0; most_critical=""
    while IFS= read -r v; do
        sev="$(awk -F'|' -v id="$v" 'index($2, id){gsub(/ /,"",$4); print $4; exit}' security-baseline.md)"
        case "$sev" in critical) rank=3 ;; high) rank=2 ;; medium) rank=1 ;; *) rank=0 ;; esac
        if [ "$rank" -gt "$best_rank" ]; then best_rank=$rank; most_critical="$v"; fi
    done <<EOF_V
$sorted
EOF_V

    json_array="$(printf '"%s",' $sorted)"; json_array="[${json_array%,}]"
    write_submission \
        "{ \"violations\": $json_array, \"most_critical\": \"$most_critical\" }" \
        "逐条对照 security-baseline.md 与 app.yaml：违规 $(printf '%s ' $sorted)（按编号升序）；其中 $most_critical 在基线中级别最高"
else
    echo "未识别的任务材料，放弃本轮" >&2
    exit 1
fi
