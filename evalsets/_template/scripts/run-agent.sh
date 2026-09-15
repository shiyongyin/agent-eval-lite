#!/usr/bin/env bash
# 团队 Agent 接入包装器（预设 profile：claude / codex / custom）。
#
# 用法：run-agent.sh <profile>
#   claude  → Claude Code 非交互模式（claude -p）
#   codex   → Codex CLI 非交互模式（codex exec）
#   custom  → 占位；把 run_custom() 改成你们自己的 Agent 命令
#
# 框架注入的环境变量（由 agent-eval 的 cli 适配器提供）：
#   AEL_INSTRUCTIONS  当前任务说明文件（Agent 收到的唯一题面）
#   AEL_WORKSPACE     Agent 可读写工作区（也是本脚本的 cwd）
#   AEL_INBOX         提交目录；提交必须写到 "$AEL_INBOX/$AEL_ATTEMPT_ID.json"
#   AEL_ATTEMPT_ID    当前轮次 id（attempt_001 / attempt_002 …）
#   AEL_FEEDBACK      上一轮反馈 JSON 文件路径（首轮为空串）
#
# 可选覆盖（不要把模型或权限参数写死在 profile 里）：
#   AEL_AGENT_MODEL        传给 Agent CLI 的模型名
#   AEL_AGENT_EXTRA_ARGS   追加到 Agent CLI 的额外参数（按空白切分）
#
# 边界：本脚本只读取 feedback 的对外字段（feedback / failed_checks[].message / next_step），
# 不读 judge/、hidden/、traces/；模型调用失败按非零退出，让框架记该轮无提交，不伪造提交。
set -euo pipefail

profile="${1:-claude}"

die() { echo "run-agent.sh: $*" >&2; exit 2; }
need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令 $1（$2）"; }

need jq "组装 prompt 需要 jq"
[ -n "${AEL_INSTRUCTIONS:-}" ] || die "缺少 AEL_INSTRUCTIONS，本脚本必须由 agent-eval 的 cli 适配器调用"
[ -n "${AEL_INBOX:-}" ] || die "缺少 AEL_INBOX"
[ -n "${AEL_ATTEMPT_ID:-}" ] || die "缺少 AEL_ATTEMPT_ID"

# 组装 prompt：题面全文 + 本轮提交文件名 +（第二轮起）上一轮反馈的对外字段。
build_prompt() {
  cat "${AEL_INSTRUCTIONS}"
  printf '\n\n## 本轮提交\n\n'
  printf '把提交 JSON 写入：`%s/%s.json`（文件名必须完全一致，写完即结束本轮）。\n' \
    "${AEL_INBOX}" "${AEL_ATTEMPT_ID}"
  if [ -n "${AEL_FEEDBACK:-}" ]; then
    if [ -f "${AEL_FEEDBACK}" ]; then
      printf '\n## 上一轮评审反馈\n\n'
      jq -r '
        "- 反馈：" + (.feedback // "无"),
        "- 下一步：" + (.next_step // "无"),
        (if ((.failed_checks // []) | length) > 0 then
           "- 未通过的检查：\n" + ((.failed_checks | map("  - " + (.message // ""))) | join("\n"))
         else empty end)
      ' "${AEL_FEEDBACK}"
    else
      echo "run-agent.sh: AEL_FEEDBACK 指向的文件不存在，按首轮处理: ${AEL_FEEDBACK}" >&2
    fi
  fi
}

prompt="$(build_prompt)"

# 可选参数数组（bash 3.2 下空数组必须用 ${arr[@]+"${arr[@]}"} 展开）。
model_args=()
[ -n "${AEL_AGENT_MODEL:-}" ] && model_args=(--model "${AEL_AGENT_MODEL}")
extra_args=()
# shellcheck disable=SC2206
[ -n "${AEL_AGENT_EXTRA_ARGS:-}" ] && extra_args=(${AEL_AGENT_EXTRA_ARGS})

run_claude() {
  need claude "npm i -g @anthropic-ai/claude-code，或改用 docker/agent-cli.Dockerfile 镜像"
  # acceptEdits：自动接受文件编辑；--add-dir 让 inbox（在 workspace 之外）可写。
  # 需要让 Agent 跑命令（如代码修复任务）时，用 AEL_AGENT_EXTRA_ARGS 追加
  # --dangerously-skip-permissions，并建议只在 --sandbox docker 下这么做。
  exec claude -p "${prompt}" \
    --permission-mode acceptEdits \
    --add-dir "${AEL_INBOX}" \
    ${model_args[@]+"${model_args[@]}"} \
    ${extra_args[@]+"${extra_args[@]}"}
}

run_codex() {
  need codex "npm i -g @openai/codex，或改用 docker/agent-cli.Dockerfile 镜像"
  # workspace-write：只允许写 cwd（即 AEL_WORKSPACE）；--add-dir 放行 inbox。
  # network_access=true：codex 的沙箱默认连 loopback 都禁，会让 `agent-eval tool call` 回连宿主工具网关
  # 报 "Operation not permitted"（dogfooding 实测）；cli 模式本就与框架同机同信任域，这里放开。
  exec codex exec \
    --skip-git-repo-check \
    --sandbox workspace-write \
    -c 'sandbox_workspace_write.network_access=true' \
    --add-dir "${AEL_INBOX}" \
    ${model_args[@]+"${model_args[@]}"} \
    ${extra_args[@]+"${extra_args[@]}"} \
    "${prompt}"
}

run_custom() {
  # 把下面两行替换成你们的 Agent 调用；contract 只有一条：
  # 结束前把结构化提交写到 "$AEL_INBOX/$AEL_ATTEMPT_ID.json"。
  die "custom profile 尚未接入：请编辑 run_custom()，把 \$prompt 交给你们的 Agent 并写入 ${AEL_INBOX}/${AEL_ATTEMPT_ID}.json"
}

case "${profile}" in
  claude) run_claude ;;
  codex) run_codex ;;
  custom) run_custom ;;
  *) die "未知 profile: ${profile}（可选 claude / codex / custom）" ;;
esac
