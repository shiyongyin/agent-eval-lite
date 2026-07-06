package com.agenteval.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI Agent 适配器：驱动任何命令行形态的编码 Agent（claude、cursor-agent、自研 CLI 等）。
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
 * @author shiyongyin
 * @since 0.1.0
 */
public final class CliAgentAdapter implements AgentAdapter {

    private static final Logger log = LoggerFactory.getLogger(CliAgentAdapter.class);

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

        Path logFile = input.context().agentLogsDir().resolve(input.attemptId() + ".log");
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", cmd)
                .directory(input.context().workspaceDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        builder.environment().put("AEL_RUN_DIR", input.context().runDir().toAbsolutePath().toString());
        builder.environment().put("AEL_INSTRUCTIONS", input.instructionsFile().toAbsolutePath().toString());
        builder.environment().put("AEL_WORKSPACE", input.context().workspaceDir().toAbsolutePath().toString());
        builder.environment().put("AEL_INBOX", input.context().inboxDir().toAbsolutePath().toString());
        builder.environment().put("AEL_ATTEMPT_ID", input.attemptId());
        builder.environment().put("AEL_FEEDBACK", feedback);
        if (input.toolAccess() != null) {
            // 让 `agent-eval tool call` 连回框架进程内的常驻网关（由服务端代写签名 trace）。
            builder.environment().put("AEL_TOOL_ENDPOINT", input.toolAccess().endpoint());
            builder.environment().put("AEL_TOOL_TOKEN", input.toolAccess().token());
        }

        int exitCode;
        boolean timedOut = false;
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(input.timeout().toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                timedOut = true;
                exitCode = -1;
                log.warn("agent 进程超时被终止（{}s）", input.timeout().toSeconds());
            } else {
                exitCode = process.exitValue();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("启动 agent 进程失败: " + cmd, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 agent 进程被中断", e);
        }

        Path expected = input.expectedSubmissionFile();
        Path submission = Files.isRegularFile(expected) ? expected : null;
        return new AttemptOutcome(submission, !timedOut && exitCode == 0, exitCode, logFile, false);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
