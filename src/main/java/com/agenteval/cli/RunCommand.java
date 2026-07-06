package com.agenteval.cli;

import com.agenteval.agent.AgentAdapter;
import com.agenteval.agent.CliAgentAdapter;
import com.agenteval.agent.ManualAgentAdapter;
import com.agenteval.agent.ScriptedAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval run}：执行（或恢复）一次评估。
 *
 * <p>示例：
 * <pre>{@code
 * # 脚本回放（demo / CI 回归）
 * agent-eval run --task tasks/api-payload-001 --agent scripted \
 *     --script tasks/api-payload-001/samples/replay.yaml
 *
 * # 人工单发提交
 * agent-eval run --task tasks/api-payload-001 --agent manual --submission my-submission.json
 *
 * # 驱动 CLI coding agent
 * agent-eval run --task tasks/code-fix-001 --agent cli \
 *     --cmd 'claude -p "$(cat {instructions})" --dangerously-skip-permissions' --model claude-sonnet
 *
 * # 恢复被中断的 run
 * agent-eval run --resume runs/code-fix-001/run_20260706_101500_ab12cd --agent cli --cmd '...'
 * }</pre>
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "run", mixinStandardHelpOptions = true, description = "执行（或恢复）一次评估 run")
public final class RunCommand implements Callable<Integer> {

    @Option(names = "--task", description = "任务目录（新 run 必填）")
    private Path taskDir;

    @Option(names = "--agent", required = true, description = "agent 适配器: manual | scripted | cli")
    private String agent;

    @Option(names = "--submission", description = "manual 模式：单发提交文件（不提供则进入交互模式）")
    private Path submission;

    @Option(names = "--script", description = "scripted 模式：replay.yaml 路径")
    private Path script;

    @Option(names = "--cmd", description = "cli 模式：agent 命令模板（支持 {instructions} {workspace} {inbox} {attempt_id} {feedback} {run_dir} 占位符）")
    private String cmd;

    @Option(names = "--model", description = "模型标识（仅用于报告归档）")
    private String model;

    @Option(names = "--runs-root", defaultValue = "runs", description = "runs 根目录（默认 ${DEFAULT-VALUE}）")
    private Path runsRoot;

    @Option(names = "--resume", description = "恢复指定 run 目录（与 --task 二选一）")
    private Path resumeRunDir;

    @Option(names = "--fail-on-not-passed", description = "未通过时以退出码 3 结束（CI 门禁用）")
    private boolean failOnNotPassed;

    @Override
    public Integer call() {
        if (resumeRunDir == null && taskDir == null) {
            System.err.println("错误: 需要 --task <任务目录> 或 --resume <run 目录>");
            return 1;
        }
        AgentAdapter adapter;
        try {
            adapter = buildAdapter();
        } catch (IllegalArgumentException e) {
            System.err.println("错误: " + e.getMessage());
            return 1;
        }

        RunManager.RunOutcome outcome = RunManager.execute(new RunManager.RunConfig(
                taskDir, runsRoot, model, adapter, resumeRunDir));

        System.out.println();
        System.out.println("========== 评估完成 ==========");
        System.out.println("run_id : " + outcome.runId());
        System.out.println("状态   : " + outcome.status()
                + (outcome.bestScore() == null ? "" : "（最佳 " + outcome.bestScore() + " 分，" + outcome.bestAttemptId() + "）"));
        System.out.println("目录   : " + outcome.runDir());
        System.out.println("报告   : " + outcome.reportMd());
        if (outcome.status() == RunStatus.ERROR || outcome.status() == RunStatus.INTEGRITY_BROKEN) {
            return 2;
        }
        if (failOnNotPassed && outcome.status() != RunStatus.PASSED) {
            return 3;
        }
        return 0;
    }

    private AgentAdapter buildAdapter() {
        return switch (agent.toLowerCase()) {
            case "manual" -> new ManualAgentAdapter(submission);
            case "scripted" -> {
                if (script == null) {
                    throw new IllegalArgumentException("scripted 模式需要 --script <replay.yaml>");
                }
                yield new ScriptedAgentAdapter(script);
            }
            case "cli" -> {
                if (cmd == null || cmd.isBlank()) {
                    throw new IllegalArgumentException("cli 模式需要 --cmd <命令模板>");
                }
                yield new CliAgentAdapter(cmd);
            }
            default -> throw new IllegalArgumentException("未知 agent 适配器: " + agent);
        };
    }
}
