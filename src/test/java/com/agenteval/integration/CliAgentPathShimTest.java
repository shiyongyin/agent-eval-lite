package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.agent.CliAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * instructions.md 告诉 Agent 用 {@code agent-eval tool call} 调工具，那么 cli Agent 的子进程 PATH 上就必须
 * 真的有 {@code agent-eval}——不能指望用户把 bin/ 加进 PATH（dogfooding 实测：真实 Agent 直接 command not found）。
 * 框架在 run 目录下放一个指向当前 JVM/classpath 的垫片并前置到 PATH。
 */
class CliAgentPathShimTest {

    @TempDir
    Path runsRoot;

    @Test
    void cli子进程PATH上有agent_eval垫片_且能真正执行框架命令() throws Exception {
        Path sample = Path.of("tasks/api-payload-001/samples/attempt-pass.json").toAbsolutePath();
        // 先记录 agent-eval 解析到哪、再用它真跑一条框架命令（--version，不依赖 cwd），成功后才写提交。
        String cmd = "command -v agent-eval > \"$AEL_WORKSPACE/.which-agent-eval\" && "
                + "agent-eval --version > \"$AEL_WORKSPACE/.version-output\" 2>&1 && "
                + "sed \"s/[{]attempt_id[}]/${AEL_ATTEMPT_ID}/\" '" + sample
                + "' > \"${AEL_INBOX}/${AEL_ATTEMPT_ID}.json\"";

        RunManager.RunOutcome outcome = RunManager.run(Path.of("tasks/api-payload-001"), runsRoot,
                null, "shim-test", new CliAgentAdapter(cmd));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        Path workspace = outcome.runDir().resolve("workspace");
        String which = Files.readString(workspace.resolve(".which-agent-eval")).trim();
        // 垫片位于本 run 目录内的框架私有区，而不是恰好命中开发者机器上的某个 agent-eval。
        assertThat(Path.of(which).toAbsolutePath().normalize().startsWith(outcome.runDir().toAbsolutePath().normalize()))
                .as("agent-eval 应解析到 run 目录内的垫片，实际 %s", which).isTrue();
        assertThat(Files.readString(workspace.resolve(".version-output"))).contains("agent-eval-lite");
    }
}
