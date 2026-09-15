# AgentEval-Lite 沙箱内运行 Agent CLI 的示例镜像（Claude Code + Codex CLI）。
#
# 这是「Agent 运行环境」镜像，与 CI 红队用的 alpine 隔离回归镜像是两件事：后者证明容器挡得住
# 逃逸，前者让真实 Agent 能在容器里干活。镜像里不含任何凭证，除接入包装器 run-agent.sh 之外
# 不 COPY 仓库文件（.dockerignore 只放行这一个文件）；凭证在运行时经 --sandbox-docker-arg 透传。
# 注意 --sandbox-docker-arg 是「一个 token 一个参数」：写 --sandbox-docker-arg=-e --sandbox-docker-arg=OPENAI_API_KEY，
# 不要写 '-e OPENAI_API_KEY'（会被当成一个带空格的 token 交给 docker）。
#
# 构建（在仓库根目录）：
#   docker build -f docker/agent-cli.Dockerfile -t ael-agent-cli .
#
# 使用（需要模型 API 时必须显式 bridge 联网；默认 --sandbox-network none 全程断网）：
#   bin/agent-eval run --task tasks/api-payload-001 --agent cli \
#     --sandbox docker --sandbox-image ael-agent-cli --sandbox-network bridge \
#     --sandbox-docker-arg=-e --sandbox-docker-arg=ANTHROPIC_API_KEY \
#     --cmd 'ael-run-agent claude' --label claude-docker
#
# Codex 走自定义 provider 时，把只含 provider 配置的 config.toml 只读挂进容器（目录已预建为 agent 属主）：
#     --sandbox-docker-arg=-v --sandbox-docker-arg=/path/to/config.toml:/home/agent/.codex/config.toml:ro
#
# 容器内约定：
#   ael-run-agent <profile>   = evalsets/_template/scripts/run-agent.sh 的镜像内副本
#   agent-eval tool call ...  = 经框架挂进来的工具客户端 jar（AEL_TOOL_JAR）回连宿主网关，
#                               instructions.md 里写的调用方式在容器内原样可用
#
# 升级 CLI 版本：改下面两个 ARG 并在 PR 里说明；生产使用建议固定版本号而不是 latest。
FROM node:20-bookworm-slim

ARG CLAUDE_CODE_VERSION=latest
ARG CODEX_VERSION=latest

# bash / jq：run-agent.sh 依赖；curl / git / ca-certificates / procps：Agent CLI 与代码修复类任务常用；
# JRE：容器内 `agent-eval tool call` 需要运行工具客户端 jar。
# slim 镜像去掉了 man 目录，bash / JRE 的 postinst 会因 update-alternatives 找不到 man 路径而失败，先补目录。
RUN mkdir -p /usr/share/man/man1 /usr/share/man/man7 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        bash jq curl git ca-certificates procps default-jre-headless \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" "@openai/codex@${CODEX_VERSION}" \
    && npm cache clean --force

COPY evalsets/_template/scripts/run-agent.sh /usr/local/bin/ael-run-agent
RUN chmod 0755 /usr/local/bin/ael-run-agent \
    && printf '#!/bin/sh\n[ -n "$AEL_TOOL_JAR" ] || { echo "agent-eval: 容器内未挂载工具客户端 jar（AEL_TOOL_JAR 为空）" >&2; exit 2; }\nexec java -jar "$AEL_TOOL_JAR" "$@"\n' > /usr/local/bin/agent-eval \
    && chmod 0755 /usr/local/bin/agent-eval

# 非 root 运行；HOME 可写供 CLI 落配置。工作目录由框架以 -w 指定（workspace 挂载点）。
# 预建 .codex / .claude 目录（agent 属主）：这样宿主只需把 provider 配置文件单独挂进来
# （-v <host>/config.toml:/home/agent/.codex/config.toml:ro），目录本身仍可写，CLI 不会因 root 属主目录报 Permission denied。
RUN useradd --create-home --shell /bin/bash agent \
    && mkdir -p /home/agent/.codex /home/agent/.claude \
    && chown -R agent:agent /home/agent
USER agent
ENV HOME=/home/agent
