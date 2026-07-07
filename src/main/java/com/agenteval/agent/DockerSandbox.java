package com.agenteval.agent;

import com.agenteval.task.TaskContext;
import com.agenteval.tool.ToolAccess;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 沙箱配置：把「CLI Agent 的一条命令」包进 {@code docker run --rm} 的<strong>只挂 Agent 可触碰区</strong>的容器。
 *
 * <p>存在意义（根治红队 A「外科式偷看 hidden」，见 README「安全边界」）：本地模式下 Agent 与框架
 * 同机同用户，{@code hidden/}、{@code judge/}、{@code traces/} 都在同一文件系统里，canary 只能抓
 * 「抄了带 token 的内容」，抓不住「只 {@code sed} 出答案数值」。容器把可见文件系统收敛到
 * 仅四个 Agent 应触碰的挂载点，隐藏答案根本不在容器里——偷看无从谈起，运行时 nonce 重放同样失去落点。
 *
 * <p>挂载矩阵（与设计 §2.4 权限矩阵一致）：
 * <table border="1">
 *   <caption>容器挂载点</caption>
 *   <tr><th>宿主目录</th><th>容器路径</th><th>权限</th></tr>
 *   <tr><td>{@code runDir/workspace}</td><td>{@value #CONTAINER_WORKSPACE}</td><td>rw（工作目录）</td></tr>
 *   <tr><td>{@code runDir/inbox}</td><td>{@value #CONTAINER_INBOX}</td><td>rw（唯一提交通道）</td></tr>
 *   <tr><td>{@code runDir/feedback}</td><td>{@value #CONTAINER_FEEDBACK}</td><td>ro（受控反馈）</td></tr>
 *   <tr><td>{@code runDir/instructions.md}</td><td>{@value #CONTAINER_INSTRUCTIONS}</td><td>ro（任务说明）</td></tr>
 * </table>
 * {@code hidden/}、{@code judge/}、{@code traces/}、{@code run_state.json}、任务目录、宿主家目录一律不挂载。
 *
 * <p>网络与工具网关：默认 {@code --network none}（断网，最大化隔离）。当任务需要工具且非断网时，
 * 经 {@code host.docker.internal} 回连宿主 loopback 上的常驻网关（macOS Docker Desktop 不支持
 * bind-mount unix socket，故用 host-gateway 回连；token 仍作纵深防御）。工具客户端 jar 可只读挂入
 * 供容器内 {@code java -jar} 调用（镜像需自带 JRE）。
 *
 * @param image 容器镜像（必填；须自带 Agent 命令所需的一切，如 claude/codex CLI 或红队脚本所需的 sh）
 * @param network Docker 网络模式（{@code none}=断网，默认；{@code bridge}/自定义=经 host-gateway 回连网关）
 * @param toolClientJar 只读挂入容器的 CLI jar（供容器内 {@code agent-eval tool call}；可为 {@code null}）
 * @param extraDockerArgs 透传给 {@code docker run} 的附加参数（如 {@code --memory=512m}；可为空）
 * @author shiyongyin
 * @since 0.4.0
 */
public record DockerSandbox(String image, String network, Path toolClientJar,
                            List<String> extraDockerArgs) {

    /** 容器内工作副本目录（同时是 Agent 进程 cwd）。 */
    public static final String CONTAINER_WORKSPACE = "/ael/workspace";
    /** 容器内提交收件目录。 */
    public static final String CONTAINER_INBOX = "/ael/inbox";
    /** 容器内受控反馈目录（只读）。 */
    public static final String CONTAINER_FEEDBACK = "/ael/feedback";
    /** 容器内任务说明文件（只读）。 */
    public static final String CONTAINER_INSTRUCTIONS = "/ael/instructions.md";
    /** 容器内工具客户端 jar 挂载点。 */
    public static final String CONTAINER_TOOL_JAR = "/ael/agent-eval.jar";
    /** 断网网络模式常量。 */
    public static final String NETWORK_NONE = "none";
    /** 容器内经此主机名回连宿主网关（配合 {@code --add-host host.docker.internal:host-gateway}）。 */
    private static final String HOST_GATEWAY_NAME = "host.docker.internal";

    /**
     * 规范化构造：校验必填项、补默认网络、冻结附加参数列表。
     *
     * @param image 容器镜像
     * @param network 网络模式（{@code null}/空视为 {@value #NETWORK_NONE}）
     * @param toolClientJar 工具客户端 jar（可为 {@code null}）
     * @param extraDockerArgs 附加 docker 参数（{@code null} 视为空）
     */
    public DockerSandbox {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("docker 沙箱需要镜像名（--sandbox-image）");
        }
        image = image.trim();
        network = (network == null || network.isBlank()) ? NETWORK_NONE : network.trim();
        extraDockerArgs = extraDockerArgs == null ? List.of() : List.copyOf(extraDockerArgs);
    }

    /**
     * 便捷构造：仅指定镜像，默认断网、无工具 jar、无附加参数。
     *
     * @param image 容器镜像
     * @return 沙箱配置
     */
    public static DockerSandbox ofImage(String image) {
        return new DockerSandbox(image, NETWORK_NONE, null, List.of());
    }

    /** @return 是否断网（{@code --network none}） */
    public boolean networkDisabled() {
        return NETWORK_NONE.equals(network);
    }

    /**
     * 将模板中的占位符替换为<strong>容器内</strong>路径（区别于宿主路径），并做 shell 单引号安全包裹。
     *
     * <p>{@code {run_dir}} 在容器内无对应挂载（run 目录含评审禁区，故意不挂载），替换为空串；
     * Agent 需调工具时改用注入的 {@code AEL_TOOL_ENDPOINT} 而非 {@code AEL_RUN_DIR}。
     *
     * @param template 命令模板
     * @param attemptId 本轮 attempt id
     * @param feedbackContainerPath 容器内反馈文件路径（首轮为空串）
     * @return 替换后的容器内命令
     */
    public String substitutePlaceholders(String template, String attemptId, String feedbackContainerPath) {
        return template
                .replace("{instructions}", quote(CONTAINER_INSTRUCTIONS))
                .replace("{workspace}", quote(CONTAINER_WORKSPACE))
                .replace("{inbox}", quote(CONTAINER_INBOX))
                .replace("{attempt_id}", attemptId)
                .replace("{feedback}", quote(feedbackContainerPath))
                .replace("{run_dir}", quote(""));
    }

    /**
     * 构造完整的 {@code docker run} 参数向量（不含内层命令的 shell 解析——内层命令作为单个参数交给容器内 {@code /bin/sh -c}）。
     *
     * <p>纯函数、无副作用，便于单元测试断言挂载点、只读位、网络模式与工具环境注入是否符合安全约束。
     *
     * @param ctx run 上下文（提供宿主侧 workspace/inbox/feedback/instructions 路径）
     * @param containerName 容器名（供超时后 {@code docker rm -f} 精确清理）
     * @param innerCommand 容器内待执行命令（已完成占位符替换）
     * @param attemptId 本轮 attempt id（注入 {@code AEL_ATTEMPT_ID}）
     * @param feedbackContainerPath 容器内反馈文件路径（注入 {@code AEL_FEEDBACK}；首轮为空串）
     * @param toolAccess 工具调用能力（非断网时据此注入回连宿主网关的端点/凭证；可为 {@code null}）
     * @return {@code docker} 可执行文件起头的完整参数向量
     */
    public List<String> buildRunArgv(TaskContext ctx, String containerName, String innerCommand,
                                     String attemptId, String feedbackContainerPath, ToolAccess toolAccess) {
        List<String> argv = new ArrayList<>();
        argv.add("docker");
        argv.add("run");
        argv.add("--rm");
        argv.add("--name");
        argv.add(containerName);

        argv.add("--network");
        argv.add(network);

        boolean toolBridge = toolAccess != null && !networkDisabled();
        if (toolBridge) {
            // macOS Docker Desktop 不支持 bind-mount unix socket，故经 host-gateway 回连宿主 loopback 网关。
            argv.add("--add-host");
            argv.add(HOST_GATEWAY_NAME + ":host-gateway");
        }

        addMount(argv, ctx.workspaceDir(), CONTAINER_WORKSPACE, false);
        addMount(argv, ctx.inboxDir(), CONTAINER_INBOX, false);
        addMount(argv, ctx.feedbackDir(), CONTAINER_FEEDBACK, true);
        addMount(argv, ctx.instructionsFile(), CONTAINER_INSTRUCTIONS, true);
        if (toolClientJar != null) {
            addMount(argv, toolClientJar, CONTAINER_TOOL_JAR, true);
        }

        argv.add("-w");
        argv.add(CONTAINER_WORKSPACE);

        addEnv(argv, "AEL_WORKSPACE", CONTAINER_WORKSPACE);
        addEnv(argv, "AEL_INBOX", CONTAINER_INBOX);
        addEnv(argv, "AEL_INSTRUCTIONS", CONTAINER_INSTRUCTIONS);
        addEnv(argv, "AEL_ATTEMPT_ID", attemptId);
        addEnv(argv, "AEL_FEEDBACK", feedbackContainerPath);
        if (toolBridge) {
            addEnv(argv, "AEL_TOOL_ENDPOINT", rewriteEndpointForContainer(toolAccess.endpoint()));
            addEnv(argv, "AEL_TOOL_TOKEN", toolAccess.token());
            if (toolClientJar != null) {
                addEnv(argv, "AEL_TOOL_JAR", CONTAINER_TOOL_JAR);
            }
        }

        argv.addAll(extraDockerArgs);

        argv.add(image);
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(innerCommand);
        return argv;
    }

    /**
     * 把宿主 loopback 端点的主机部分改写为 {@value #HOST_GATEWAY_NAME}，端口保持不变。
     *
     * @param hostEndpoint 宿主端点（形如 {@code 127.0.0.1:54321}）
     * @return 容器内可达的端点（形如 {@code host.docker.internal:54321}）
     */
    public static String rewriteEndpointForContainer(String hostEndpoint) {
        int colon = hostEndpoint.lastIndexOf(':');
        if (colon < 0) {
            return hostEndpoint;
        }
        return HOST_GATEWAY_NAME + hostEndpoint.substring(colon);
    }

    private static void addMount(List<String> argv, Path hostPath, String containerPath, boolean readOnly) {
        argv.add("-v");
        String spec = hostPath.toAbsolutePath().normalize() + ":" + containerPath + (readOnly ? ":ro" : ":rw");
        argv.add(spec);
    }

    private static void addEnv(List<String> argv, String key, String value) {
        argv.add("-e");
        argv.add(key + "=" + (value == null ? "" : value));
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
