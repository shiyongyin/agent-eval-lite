package com.agenteval.agent;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Docker 可用性预检：在启用 {@code --sandbox docker} 前确认 docker CLI 存在且 daemon 在跑。
 *
 * <p>动机：docker 未安装或 daemon 未启动是「评估基础设施未就绪」，应在启动 run 之前以清晰文案前移拦截，
 * 而不是让 Agent 进程启动阶段抛出晦涩的 {@code IOException}，更不能被误记为 Agent 低分。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
public final class DockerAvailability {

    private DockerAvailability() {
    }

    /**
     * 预检结论。
     *
     * @param available docker CLI 与 daemon 均就绪
     * @param detail 不可用时的人类可读原因（可用时为空串）
     */
    public record Status(boolean available, String detail) {
    }

    /**
     * 执行预检（运行 {@code docker version --format ...} 探测 Server 是否响应）。
     *
     * @return 预检结论
     */
    public static Status check() {
        try {
            Process process = new ProcessBuilder(
                    "docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Status(false, "docker version 探测超时（daemon 可能无响应）");
            }
            if (process.exitValue() == 0) {
                return new Status(true, "");
            }
            return new Status(false, "docker daemon 未响应（请先启动 Docker Desktop / dockerd）");
        } catch (IOException e) {
            return new Status(false, "未找到 docker 可执行文件（请先安装 Docker 并加入 PATH）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(false, "docker 预检被中断");
        }
    }
}
