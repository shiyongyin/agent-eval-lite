package com.agenteval.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子进程型 Agent 适配器的共享执行内核：启动进程、合流落盘、按预算超时强杀。
 *
 * <p>{@link CliAgentAdapter}（宿主直跑）与 {@link DockerAgentAdapter}（容器隔离）复用同一套
 * 进程生命周期与超时语义，差异只在「跑什么命令、注入哪些环境、超时后如何补充清理」——这些通过
 * 参数注入，避免两个适配器各写一遍 {@code waitFor/destroyForcibly} 而语义漂移。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
final class AgentProcess {

    private static final Logger log = LoggerFactory.getLogger(AgentProcess.class);

    private AgentProcess() {
    }

    /**
     * 一次进程执行的结果。
     *
     * @param exitCode 退出码（超时强杀记为 {@code -1}）
     * @param timedOut 是否因超时被强制终止
     */
    record Result(int exitCode, boolean timedOut) {
    }

    /**
     * 启动并等待一个子进程，stdout/stderr 合流写入日志文件，超时即强杀。
     *
     * @param command 完整命令向量（首元素为可执行文件）
     * @param extraEnv 追加到子进程环境的变量（{@code null} 表示不追加，仅继承宿主环境）
     * @param workingDir 进程工作目录
     * @param logFile 合流输出落点（覆盖写）
     * @param timeout 执行预算
     * @param onTimeout 超时强杀后的补充清理（如 {@code docker rm -f}；可为 {@code null}）
     * @return 执行结果
     */
    static Result run(List<String> command, Map<String, String> extraEnv, Path workingDir,
                      Path logFile, Duration timeout, Runnable onTimeout) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        if (extraEnv != null) {
            builder.environment().putAll(extraEnv);
        }

        try {
            Process process = builder.start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (finished) {
                return new Result(process.exitValue(), false);
            }
            process.destroyForcibly();
            if (onTimeout != null) {
                onTimeout.run();
            }
            log.warn("agent 进程超时被终止（{}s）", timeout.toSeconds());
            return new Result(-1, true);
        } catch (IOException e) {
            throw new UncheckedIOException("启动 agent 进程失败: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 agent 进程被中断", e);
        }
    }
}
