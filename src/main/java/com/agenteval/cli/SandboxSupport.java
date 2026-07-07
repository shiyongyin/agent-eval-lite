package com.agenteval.cli;

import com.agenteval.agent.DockerAvailability;
import com.agenteval.agent.DockerSandbox;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 沙箱选项的共享构建逻辑：{@code run} 与 {@code suite} 用同一口径解析 {@code --sandbox docker} 相关参数。
 *
 * <p>集中三件事：判定是否启用 docker、构造并校验 {@link DockerSandbox}（含 docker 可用性预检）、
 * 自动探测当前运行 jar 作为容器内工具客户端——避免两个命令各写一遍导致语义漂移。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
final class SandboxSupport {

    static final String SANDBOX_NONE = "none";
    static final String SANDBOX_DOCKER = "docker";

    private SandboxSupport() {
    }

    /**
     * 是否请求 docker 沙箱。
     *
     * @param sandbox {@code --sandbox} 取值（大小写不敏感；{@code null}/空视为 none）
     * @return 请求 docker 时为 {@code true}
     */
    static boolean isDocker(String sandbox) {
        return sandbox != null && SANDBOX_DOCKER.equals(sandbox.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 校验 {@code --sandbox} 取值合法。
     *
     * @param sandbox 取值
     * @throws IllegalArgumentException 非 none/docker
     */
    static void validateSandboxValue(String sandbox) {
        if (sandbox == null || sandbox.isBlank()) {
            return;
        }
        String normalized = sandbox.trim().toLowerCase(Locale.ROOT);
        if (!SANDBOX_NONE.equals(normalized) && !SANDBOX_DOCKER.equals(normalized)) {
            throw new IllegalArgumentException("未知 --sandbox 取值: " + sandbox + "（可选 none / docker）");
        }
    }

    /**
     * 构造 docker 沙箱配置：校验镜像、做 docker 可用性预检、解析容器内工具客户端 jar。
     *
     * @param image {@code --sandbox-image}
     * @param network {@code --sandbox-network}（{@code null}/空默认断网）
     * @param explicitToolJar {@code --sandbox-tool-jar}（{@code null} 则自动探测运行 jar）
     * @param extraArgs 透传 docker 的附加参数（可为 {@code null}）
     * @return 沙箱配置
     * @throws IllegalArgumentException 镜像缺失、docker 不可用或工具 jar 路径非法
     */
    static DockerSandbox build(String image, String network, Path explicitToolJar, List<String> extraArgs) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("--sandbox docker 需要 --sandbox-image <镜像>");
        }
        DockerAvailability.Status status = DockerAvailability.check();
        if (!status.available()) {
            throw new IllegalArgumentException("docker 沙箱不可用: " + status.detail());
        }
        Path toolJar = resolveToolJar(explicitToolJar);
        return new DockerSandbox(image, network, toolJar, extraArgs);
    }

    /**
     * 解析容器内工具客户端 jar：显式指定优先，否则自动探测当前运行的 CLI jar。
     *
     * @param explicit 显式路径（可为 {@code null}）
     * @return 可用的 jar 路径；无法确定时为 {@code null}（此时容器内无法经 jar 调工具，仅注入网关端点）
     * @throws IllegalArgumentException 显式路径不存在
     */
    static Path resolveToolJar(Path explicit) {
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                throw new IllegalArgumentException("--sandbox-tool-jar 不存在: " + explicit);
            }
            return explicit.toAbsolutePath().normalize();
        }
        return autoDetectRunningJar();
    }

    private static Path autoDetectRunningJar() {
        try {
            Path self = Path.of(SandboxSupport.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(self) && self.getFileName().toString().endsWith(".jar")) {
                return self.toAbsolutePath().normalize();
            }
        } catch (URISyntaxException | RuntimeException e) {
            // 运行于 classes 目录（如测试）或无法定位——容器内工具客户端交由用户显式提供。
            return null;
        }
        return null;
    }
}
