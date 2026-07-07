package com.agenteval.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker 沙箱 CLI Agent 适配器：把 {@link CliAgentAdapter} 的命令包进只挂 Agent 可触碰区的容器。
 *
 * <p>与 {@link CliAgentAdapter} 的唯一差异是<strong>执行边界</strong>：命令不在宿主直跑，而是经
 * {@code docker run --rm} 在容器内执行，容器仅挂载 {@code workspace/}(rw)、{@code inbox/}(rw)、
 * {@code feedback/}(ro)、{@code instructions.md}(ro)（见 {@link DockerSandbox} 的挂载矩阵）。
 * 因此 {@code hidden/}、{@code judge/}、{@code traces/}、任务目录、宿主家目录都不在容器文件系统内——
 * 红队 A「外科式偷看 hidden」失去物证、运行时 nonce 重放失去落点。默认 {@code --network none} 断网。
 *
 * <p>占位符替换用<strong>容器内路径</strong>（{@code /ael/workspace} 等）；提交仍写进 inbox
 * （bind-mount 到宿主，容器退出后框架在宿主侧原路径收件）。超时强杀后额外 {@code docker rm -f}
 * 兜底清理可能残留的容器。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
public final class DockerAgentAdapter implements AgentAdapter {

    private static final Logger log = LoggerFactory.getLogger(DockerAgentAdapter.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String commandTemplate;
    private final DockerSandbox sandbox;

    /**
     * 构造适配器。
     *
     * @param commandTemplate 含占位符的命令模板（与 cli 模式同占位符，替换为容器内路径）
     * @param sandbox docker 沙箱配置
     */
    public DockerAgentAdapter(String commandTemplate, DockerSandbox sandbox) {
        if (commandTemplate == null || commandTemplate.isBlank()) {
            throw new IllegalArgumentException("docker 沙箱需要命令模板（--cmd）");
        }
        this.commandTemplate = commandTemplate;
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return "docker";
    }

    /** docker CLI 自身故障的保留退出码（daemon 错误 / 镜像不存在等，非容器内命令退出码）。 */
    private static final int DOCKER_CLI_ERROR_EXIT = 125;

    @Override
    public AttemptOutcome runAttempt(AttemptInput input) {
        if (!input.context().spec().allowedTools().isEmpty() && sandbox.networkDisabled()) {
            log.warn("任务 {} 配置了工具，但沙箱为断网模式（--sandbox-network none），容器内无法调用工具网关；"
                    + "如需工具改用 --sandbox-network bridge", input.context().spec().taskId());
        }
        String feedbackContainerPath = input.previousFeedbackFile() == null
                ? "" : containerFeedbackPath(input);
        String innerCommand = sandbox.substitutePlaceholders(
                commandTemplate, input.attemptId(), feedbackContainerPath);

        String containerName = containerName(input.attemptId());
        List<String> argv = sandbox.buildRunArgv(input.context(), containerName, innerCommand,
                input.attemptId(), feedbackContainerPath, input.toolAccess());

        Path logFile = input.context().agentLogsDir().resolve(input.attemptId() + ".log");
        AgentProcess.Result result = AgentProcess.run(
                argv, null, input.context().workspaceDir(), logFile, input.timeout(),
                () -> forceRemove(containerName));

        if (result.exitCode() == DOCKER_CLI_ERROR_EXIT) {
            // docker 自身失败（daemon 故障 / 镜像拉取失败等）= 评估基础设施故障 ≠ Agent 低分，
            // 与 HTTP 适配器「服务不可达」同语义：直接上抛终止 run。
            throw new IllegalStateException(
                    "docker run 失败（退出码 125，docker 基础设施错误），详见日志: " + logFile
                            + (lastLogLine(logFile).isBlank() ? "" : "，末行: " + lastLogLine(logFile)));
        }

        Path expected = input.expectedSubmissionFile();
        Path submission = Files.isRegularFile(expected) ? expected : null;
        return new AttemptOutcome(submission, !result.timedOut() && result.exitCode() == 0,
                result.exitCode(), logFile, false);
    }

    private static String lastLogLine(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (!lines.get(i).isBlank()) {
                    return lines.get(i);
                }
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }

    /**
     * 反向映射：宿主反馈文件在容器内的只读路径（feedback 目录 bind-mount 到 {@code /ael/feedback}）。
     *
     * @param input 本轮输入
     * @return 容器内反馈文件路径
     */
    private static String containerFeedbackPath(AttemptInput input) {
        Path host = input.previousFeedbackFile().toAbsolutePath().normalize();
        Path feedbackDir = input.context().feedbackDir().toAbsolutePath().normalize();
        Path relative = feedbackDir.relativize(host);
        return DockerSandbox.CONTAINER_FEEDBACK + "/" + relative.toString().replace('\\', '/');
    }

    private static String containerName(String attemptId) {
        return "ael-" + attemptId.replace('_', '-') + "-" + HexFormat.of().formatHex(randomBytes());
    }

    private static void forceRemove(String containerName) {
        try {
            Process rm = new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            rm.waitFor();
        } catch (IOException e) {
            log.debug("超时后清理容器失败（忽略）: {}", containerName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
