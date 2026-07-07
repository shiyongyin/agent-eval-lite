#!/usr/bin/env bash
# 示例被评 Agent（朴素版）：故意保留两类真实 Agent 的常见毛病，用于对比面板——
#   - log-triage：全文大小写不敏感 grep "error"，不区分日志级别（会数多）；
#   - config-audit：只会看配置里显眼的开关，漏掉明文凭证，也不对照基线级别。
# 它不读反馈，每轮交同一份答案（演示「不会从反馈学习」的 Agent 长什么样）。
set -euo pipefail

cd "$AEL_WORKSPACE"
task_id="$(awk -F'`' '/任务 ID/{print $2; exit}' "$AEL_INSTRUCTIONS")"

write_submission() { # $1=answer片段(JSON)  $2=summary
    cat > "$AEL_INBOX/$AEL_ATTEMPT_ID.json" <<EOF
{
  "schema_version": 1,
  "task_id": "$task_id",
  "attempt_id": "$AEL_ATTEMPT_ID",
  "submission_type": "generic",
  "summary": "$2",
  "answer": $1,
  "known_risks": [],
  "needs_human_review": false
}
EOF
}

if [ -f service.log ]; then
    count="$(grep -ci 'error' service.log || true)"
    top="$(grep -oE 'code=[A-Z]+-[0-9]+' service.log | sed 's/^code=//' \
        | sort | uniq -c | sort -rn | awk 'NR==1{print $2}')"
    write_submission \
        "{ \"error_count\": $count, \"top_error_code\": \"$top\" }" \
        "在 service.log 全文搜索 error 共 $count 行，最常见错误码 $top"

elif [ -f app.yaml ]; then
    write_submission \
        '{ "violations": ["SEC-2", "SEC-5"], "most_critical": "SEC-2" }' \
        "看了 app.yaml：debug 没关、tls 是关闭的，先修 debug"
else
    echo "未识别的任务材料，放弃本轮" >&2
    exit 1
fi
