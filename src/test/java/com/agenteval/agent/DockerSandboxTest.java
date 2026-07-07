package com.agenteval.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenteval.task.TaskContext;
import com.agenteval.testsupport.TestSpecs;
import com.agenteval.tool.ToolAccess;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker 沙箱参数构造的安全契约回归：挂载矩阵（只挂 Agent 可触碰区、只读位正确）、
 * 默认断网、工具网关回连改写、占位符容器路径替换——argv 构造是纯函数，逐项钉死，
 * 防止未来改动悄悄把 hidden/任务目录挂进容器或把只读位放开。
 */
class DockerSandboxTest {

    @TempDir
    Path dir;

    private TaskContext context() {
        return TaskContext.of(TestSpecs.singleDimension("sandbox-test"),
                dir.resolve("task"), dir.resolve("run"));
    }

    @Test
    void 挂载矩阵_只含四个Agent可触碰区且只读位正确() {
        TaskContext ctx = context();
        DockerSandbox sandbox = DockerSandbox.ofImage("alpine:latest");

        List<String> argv = sandbox.buildRunArgv(ctx, "ael-test", "echo hi",
                "attempt_001", "", null);

        List<String> mounts = mountSpecs(argv);
        assertThat(mounts).containsExactly(
                ctx.workspaceDir() + ":/ael/workspace:rw",
                ctx.inboxDir() + ":/ael/inbox:rw",
                ctx.feedbackDir() + ":/ael/feedback:ro",
                ctx.instructionsFile() + ":/ael/instructions.md:ro");

        // 评审禁区与任务目录绝不入容器。
        String joined = String.join(" ", argv);
        assertThat(joined).doesNotContain("hidden");
        assertThat(joined).doesNotContain(ctx.judgeDir().toString());
        assertThat(joined).doesNotContain(ctx.tracesDir().toString());
        assertThat(joined).doesNotContain(ctx.taskDir() + ":");
    }

    @Test
    void 默认断网_不注入host_gateway与工具端点() {
        TaskContext ctx = context();
        DockerSandbox sandbox = DockerSandbox.ofImage("alpine:latest");
        ToolAccess toolAccess = new ToolAccess(null, "127.0.0.1:54321", "tok");

        List<String> argv = sandbox.buildRunArgv(ctx, "ael-test", "echo hi",
                "attempt_001", "", toolAccess);

        assertThat(argv).containsSequence("--network", "none");
        assertThat(argv).doesNotContain("--add-host");
        assertThat(String.join(" ", argv)).doesNotContain("AEL_TOOL_ENDPOINT");
        assertThat(String.join(" ", argv)).doesNotContain("tok");
    }

    @Test
    void bridge网络_经host_gateway回连网关并注入改写后的端点() {
        TaskContext ctx = context();
        Path jar = dir.resolve("cli.jar");
        DockerSandbox sandbox = new DockerSandbox("temurin:17", "bridge", jar, List.of());
        ToolAccess toolAccess = new ToolAccess(null, "127.0.0.1:54321", "secret-token");

        List<String> argv = sandbox.buildRunArgv(ctx, "ael-test", "echo hi",
                "attempt_001", "", toolAccess);

        assertThat(argv).containsSequence("--network", "bridge");
        assertThat(argv).containsSequence("--add-host", "host.docker.internal:host-gateway");
        assertThat(argv).containsSequence("-e", "AEL_TOOL_ENDPOINT=host.docker.internal:54321");
        assertThat(argv).containsSequence("-e", "AEL_TOOL_TOKEN=secret-token");
        assertThat(argv).containsSequence("-e", "AEL_TOOL_JAR=/ael/agent-eval.jar");
        assertThat(mountSpecs(argv)).contains(jar.toAbsolutePath() + ":/ael/agent-eval.jar:ro");
    }

    @Test
    void 占位符替换为容器内路径_run_dir故意置空() {
        DockerSandbox sandbox = DockerSandbox.ofImage("alpine:latest");

        String cmd = sandbox.substitutePlaceholders(
                "run {instructions} {workspace} {inbox} {attempt_id} {feedback} {run_dir}",
                "attempt_002", "/ael/feedback/attempt_001.feedback.json");

        assertThat(cmd).isEqualTo("run '/ael/instructions.md' '/ael/workspace' '/ael/inbox' "
                + "attempt_002 '/ael/feedback/attempt_001.feedback.json' ''");
    }

    @Test
    void 附加docker参数_出现在镜像名之前() {
        TaskContext ctx = context();
        DockerSandbox sandbox = new DockerSandbox("alpine:latest", null, null,
                List.of("--memory=512m", "--cpus=1"));

        List<String> argv = sandbox.buildRunArgv(ctx, "ael-test", "echo hi",
                "attempt_001", "", null);

        assertThat(argv.indexOf("--memory=512m")).isLessThan(argv.indexOf("alpine:latest"));
        assertThat(argv.indexOf("--cpus=1")).isLessThan(argv.indexOf("alpine:latest"));
        // 内层命令始终是最后三个参数：/bin/sh -c <cmd>。
        assertThat(argv.subList(argv.size() - 3, argv.size()))
                .containsExactly("/bin/sh", "-c", "echo hi");
    }

    @Test
    void 网络模式空白_归一为断网() {
        assertThat(new DockerSandbox("img", "  ", null, null).networkDisabled()).isTrue();
        assertThat(new DockerSandbox("img", null, null, null).network()).isEqualTo("none");
        assertThat(new DockerSandbox("img", "bridge", null, null).networkDisabled()).isFalse();
    }

    @Test
    void 镜像缺失_构造即拒绝() {
        assertThatThrownBy(() -> new DockerSandbox(" ", "none", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("镜像");
    }

    @Test
    void 端点改写_仅替换主机部分保留端口() {
        assertThat(DockerSandbox.rewriteEndpointForContainer("127.0.0.1:54321"))
                .isEqualTo("host.docker.internal:54321");
    }

    @Test
    void 空白命令模板_适配器构造即拒绝() {
        DockerSandbox sandbox = DockerSandbox.ofImage("alpine:latest");
        assertThatThrownBy(() -> new DockerAgentAdapter("  ", sandbox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--cmd");
    }

    /** 提取 argv 中全部 {@code -v} 的挂载 spec（保持顺序）。 */
    private static List<String> mountSpecs(List<String> argv) {
        java.util.List<String> mounts = new java.util.ArrayList<>();
        for (int i = 0; i < argv.size() - 1; i++) {
            if ("-v".equals(argv.get(i))) {
                mounts.add(argv.get(i + 1));
            }
        }
        return mounts;
    }
}
