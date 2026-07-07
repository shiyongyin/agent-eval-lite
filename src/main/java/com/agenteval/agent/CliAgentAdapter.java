package com.agenteval.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI Agent 适配器：驱动任何命令行形态的编码 Agent（claude、cursor-agent、自研 CLI 等），进程直跑在宿主上。
 *
 * <p>命令模板支持占位符（自动做 shell 单引号安全包裹）：
 * {@code {instructions}} / {@code {workspace}} / {@code {inbox}} / {@code {attempt_id}} /
 * {@code {feedback}}（首轮为空串）/ {@code {run_dir}}。同时注入环境变量
 * {@code AEL_RUN_DIR / AEL_INSTRUCTIONS / AEL_WORKSPACE / AEL_INBOX / AEL_ATTEMPT_ID / AEL_FEEDBACK}，
 * 供 Agent 内部及 {@code agent-eval tool call} 使用。
 *
 * <p>进程 cwd 固定为 workspace；stdout/stderr 合流落盘 {@code agent-logs/attempt_NNN.log}；
 * 超时即强杀（本轮按无提交处理，预算控制权在框架不在 Agent）。
 *
 * <p>安全边界：本适配器与框架同机同用户，{@code hidden/} 等评审禁区在同一文件系统内，只靠目录约定与
 * canary/指纹守卫（详见 README「安全边界」）。需要对不可信 Agent 做强隔离时改用 {@link DockerAgentAdapter}。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class CliAgentAdapter implements AgentAdapter {

    private final String commandTemplate;

    /**
     * 构造适配器。
     *
     * @param commandTemplate 含占位符的 shell 命令模板
     */
    public CliAgentAdapter(String commandTemplate) {
        this.commandTemplate = commandTemplate;
    }

    @Override
    public String name() {
        return "cli";
    }

    @Override
    public AttemptOutcome runAttempt(AttemptInput input) {
        String feedback = input.previousFeedbackFile() == null
                ? "" : input.previousFeedbackFile().toAbsolutePath().toString();
        String cmd = commandTemplate
                .replace("{instructions}", quote(input.instructionsFile().toAbsolutePath().toString()))
                .replace("{workspace}", quote(input.context().workspaceDir().toAbsolutePath().toString()))
                .replace("{inbox}", quote(input.context().inboxDir().toAbsolutePath().toString()))
                .replace("{attempt_id}", input.attemptId())
                .replace("{feedback}", quote(feedback))
                .replace("{run_dir}", quote(input.context().runDir().toAbsolutePath().toString()));

        Map<String, String> env = new LinkedHashMap<>();
        env.put("AEL_RUN_DIR", input.context().runDir().toAbsolutePath().toString());
        env.put("AEL_INSTRUCTIONS", input.instructionsFile().toAbsolutePath().toString());
        env.put("AEL_WORKSPACE", input.context().workspaceDir().toAbsolutePath().toString());
        env.put("AEL_INBOX", input.context().inboxDir().toAbsolutePath().toString());
        env.put("AEL_ATTEMPT_ID", input.attemptId());
        env.put("AEL_FEEDBACK", feedback);
        if (input.toolAccess() != null) {
            // 让 `agent-eval tool call` 连回框架进程内的常驻网关（由服务端代写签名 trace）。
            env.put("AEL_TOOL_ENDPOINT", input.toolAccess().endpoint());
            env.put("AEL_TOOL_TOKEN", input.toolAccess().token());
        }

        Path logFile = input.context().agentLogsDir().resolve(input.attemptId() + ".log");
        AgentProcess.Result result = AgentProcess.run(
                List.of("/bin/sh", "-c", cmd), env,
                input.context().workspaceDir(), logFile, input.timeout(), null);

        Path expected = input.expectedSubmissionFile();
        Path submission = Files.isRegularFile(expected) ? expected : null;
        return new AttemptOutcome(submission, !result.timedOut() && result.exitCode() == 0,
                result.exitCode(), logFile, false);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
