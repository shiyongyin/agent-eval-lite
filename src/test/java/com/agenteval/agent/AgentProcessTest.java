package com.agenteval.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 子进程执行内核的行为契约：真实 Agent CLI（如 codex exec）在 stdin 非 TTY 时会读到 EOF 才开始工作，
 * 框架必须给子进程一个已关闭的 stdin，否则 Agent 会一直等输入直到被超时强杀（dogfooding 实测所得）。
 */
class AgentProcessTest {

    @TempDir
    Path dir;

    @Test
    void 子进程读stdin到EOF_不会等到超时() throws Exception {
        Path log = dir.resolve("agent.log");

        AgentProcess.Result result = AgentProcess.run(
                List.of("bash", "-c", "cat >/dev/null; echo stdin-closed"),
                null, dir, log, Duration.ofSeconds(5), null);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(log)).contains("stdin-closed");
    }

    @Test
    void 子进程超时_被强杀并标记timedOut() {
        AgentProcess.Result result = AgentProcess.run(
                List.of("bash", "-c", "sleep 30"),
                null, dir, dir.resolve("agent.log"), Duration.ofSeconds(1), null);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
    }
}
