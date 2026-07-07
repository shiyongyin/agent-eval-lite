package com.agenteval.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval evalset}：私有测评集工程化辅助（当前提供 {@code init} 脚手架）。
 *
 * <p>私有测评集是小团队落地 AgentEval-Lite 的默认入口：集合目录承载团队自己的任务库、
 * Agent 接入脚本、对比清单和运行产物根目录；它独立于内置 {@code tasks/}，避免把业务题库混进
 * 框架自测门禁。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
@Command(name = "evalset", mixinStandardHelpOptions = true, description = "私有测评集工程化辅助",
        subcommands = {EvalsetCommand.InitCommand.class})
public final class EvalsetCommand {

    /**
     * {@code evalset init} 子命令：生成私有测评集骨架。
     */
    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "生成私有测评集骨架（tasks/、agents.yaml、接入脚本与落地说明）")
    public static final class InitCommand implements Callable<Integer> {

        /** 测评集 id：仓库目录名，保持 kebab-case，避免空格、斜杠和大小写混用。 */
        private static final Pattern ID_PATTERN =
                Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

        @Option(names = "--id", required = true,
                description = "测评集 id（kebab-case，如 ops-agent；同时作为目录名）")
        private String evalsetId;

        @Option(names = "--evalsets-root", defaultValue = "evalsets",
                description = "测评集根目录（默认 ${DEFAULT-VALUE}）")
        private Path evalsetsRoot;

        @Override
        public Integer call() {
            if (!ID_PATTERN.matcher(evalsetId).matches()) {
                System.err.println("错误: 测评集 id 需为 kebab-case（如 ops-agent），实际: " + evalsetId);
                return 1;
            }
            Path evalsetDir = evalsetsRoot.resolve(evalsetId);
            if (Files.exists(evalsetDir)) {
                System.err.println("错误: 目录已存在，拒绝覆盖: " + evalsetDir);
                return 1;
            }

            try {
                for (Map.Entry<String, String> entry : templates().entrySet()) {
                    Path file = evalsetDir.resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.getValue().replace("__EVALSET_ID__", evalsetId),
                            StandardCharsets.UTF_8);
                }
                evalsetDir.resolve("scripts/run-agent.sh").toFile().setExecutable(true, false);
            } catch (IOException e) {
                System.err.println("错误: 写入测评集脚手架失败: " + e.getMessage());
                return 2;
            }

            System.out.println("已生成私有测评集脚手架: " + evalsetDir);
            System.out.println("  ├── README.md              # 小团队落地说明与标准命令");
            System.out.println("  ├── agents.yaml            # 多 Agent 横评清单");
            System.out.println("  ├── scripts/run-agent.sh   # 团队 Agent 接入包装器");
            System.out.println("  └── tasks/.gitkeep         # 私有任务库（用 task init 填充）");
            System.out.println();
            System.out.println("下一步:");
            System.out.println("  1. bin/agent-eval task init --id first-task-001 --tasks-root "
                    + evalsetDir.resolve("tasks"));
            System.out.println("  2. 改写 task.yaml/work/hidden/samples 后跑 validate + scripted 回放");
            System.out.println("  3. 在 " + evalsetDir.resolve("scripts/run-agent.sh")
                    + " 接入真实 Agent，再用 agents.yaml 横评");
            return 0;
        }

        private static Map<String, String> templates() {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("README.md", """
                    # __EVALSET_ID__ 私有测评集

                    这是一个企业内部 AI Agent 私有测评集骨架。任务资产放在本目录下，运行产物放
                    `runs/`（已被根 `.gitignore` 忽略），不要把业务任务混进仓库内置 `tasks/`。

                    ## 目录约定

                    | 路径 | 用途 |
                    | --- | --- |
                    | `tasks/` | 私有任务库；每个任务结构同内置任务：`task.yaml` + `work/` + `hidden/` + `samples/` |
                    | `agents.yaml` | 多 Agent 横评清单；默认含 scripted 基线和一个待接入的 current Agent |
                    | `scripts/run-agent.sh` | 团队 Agent 接入包装器；统一处理模型、鉴权、环境变量和提交路径 |
                    | `runs/` | 评测产物；不入库 |

                    ## 从第一个任务开始

                    ```bash
                    bin/agent-eval task init --id first-task-001 --tasks-root evalsets/__EVALSET_ID__/tasks

                    bin/agent-eval validate --task evalsets/__EVALSET_ID__/tasks/first-task-001

                    bin/agent-eval run --task evalsets/__EVALSET_ID__/tasks/first-task-001 \\
                        --agent scripted \\
                        --script evalsets/__EVALSET_ID__/tasks/first-task-001/samples/replay.yaml \\
                        --runs-root evalsets/__EVALSET_ID__/runs
                    ```

                    ## 接入真实 Agent

                    先改 `scripts/run-agent.sh`，保证它读取 `AEL_*` 环境变量并把提交写到
                    `$AEL_INBOX/$AEL_ATTEMPT_ID.json`。单任务冒烟：

                    ```bash
                    bin/agent-eval run --task evalsets/__EVALSET_ID__/tasks/first-task-001 \\
                        --agent cli \\
                        --cmd 'bash "$AEL_RUN_DIR/../../../scripts/run-agent.sh" current' \\
                        --runs-root evalsets/__EVALSET_ID__/runs
                    ```

                    多 Agent 横评：

                    ```bash
                    bin/agent-eval suite --tasks-root evalsets/__EVALSET_ID__/tasks \\
                        --runs-root evalsets/__EVALSET_ID__/runs \\
                        --agents-file evalsets/__EVALSET_ID__/agents.yaml \\
                        --repeat 3
                    ```

                    ## 小团队推荐分层

                    - `smoke`：5-10 个任务，每次改 Agent / prompt 必跑。
                    - `regression`：20-50 个任务，合并前或发版前跑。
                    - `domain`：业务专项任务，不一定进硬门禁。
                    - `security`：工具权限、越权、泄露类任务。

                    任务质量标准见仓库根 `docs/07-任务质量清单.md`。
                    """);
            files.put("agents.yaml", """
                    # __EVALSET_ID__ 多 Agent 对比清单（suite --agents-file 用）。
                    #
                    # 注意：cli 命令的 cwd 是每个 run 的 workspace，引用本集合内脚本应使用
                    # $AEL_RUN_DIR 锚定。runs 布局为 <set>/runs/<task-id>/<run-id>，向上三级即集合根。
                    agents:
                      - label: baseline-scripted
                        type: scripted
                      - label: current
                        type: cli
                        cmd: 'bash "$AEL_RUN_DIR/../../../scripts/run-agent.sh" current'
                      - label: candidate
                        type: cli
                        cmd: 'bash "$AEL_RUN_DIR/../../../scripts/run-agent.sh" candidate'
                    """);
            files.put("scripts/run-agent.sh", """
                    #!/usr/bin/env bash
                    # 团队 Agent 接入包装器。
                    #
                    # 约定：
                    #   $1 = agent profile，如 current / candidate
                    #   AEL_INSTRUCTIONS = 当前任务说明文件
                    #   AEL_WORKSPACE    = Agent 可读写工作区
                    #   AEL_INBOX        = 提交目录
                    #   AEL_ATTEMPT_ID   = 当前轮次 id（文件名必须一致）
                    #   AEL_FEEDBACK     = 上一轮反馈 JSON（首轮为空）
                    #
                    # TODO：把下面占位实现替换成你的 Agent 调用，并确保最终写入：
                    #   "$AEL_INBOX/$AEL_ATTEMPT_ID.json"
                    set -euo pipefail

                    profile="${1:-current}"
                    echo "run-agent.sh 尚未接入真实 Agent（profile=$profile）" >&2
                    echo "请读取 $AEL_INSTRUCTIONS，并把结构化提交写入 $AEL_INBOX/$AEL_ATTEMPT_ID.json" >&2
                    exit 1
                    """);
            files.put("tasks/.gitkeep", "");
            return files;
        }
    }
}
