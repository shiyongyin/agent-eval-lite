package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenteval.agent.AgentAdapter;
import com.agenteval.agent.AttemptInput;
import com.agenteval.agent.AttemptOutcome;
import com.agenteval.agent.ScriptedAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Dirs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 轻量 Auto-Resume 端到端测试：进程「猝死」后凭 run_state 续跑，以及
 * resume 前 hidden 被篡改时的完整性熔断。
 */
class ResumeEndToEndTest {

    @TempDir
    Path runsRoot;

    @TempDir
    Path scratch;

    /**
     * 第 1 轮投递失败样例、第 2 轮抛异常模拟进程被杀的适配器。
     */
    private static AgentAdapter dieOnSecondAttempt(Path taskDir) {
        AgentAdapter delegate = new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml"));
        return new AgentAdapter() {
            @Override
            public String name() {
                return "flaky";
            }

            @Override
            public AttemptOutcome runAttempt(AttemptInput input) {
                if (input.attemptNumber() >= 2) {
                    throw new IllegalStateException("模拟进程在第 2 轮被杀");
                }
                return delegate.runAttempt(input);
            }
        };
    }

    @Test
    void 中断后resume从下一轮继续并最终通过() {
        Path taskDir = Path.of("tasks", "api-payload-001");

        assertThatThrownBy(() -> RunManager.run(taskDir, runsRoot, null, dieOnSecondAttempt(taskDir)))
                .hasMessageContaining("模拟进程在第 2 轮被杀");

        // 找到刚才的 run 目录：快照应停留在 RUNNING、已记录 1 轮。
        Path runDir = findSingleRunDir(taskDir);
        assertThat(runDir.resolve("run_state.json")).isRegularFile();

        RunManager.RunOutcome outcome = RunManager.resume(runDir,
                new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_002");
        assertThat(TraceLogger.readAll(runDir.resolve("traces/trace.jsonl")))
                .extracting(e -> e.path("type").asText())
                .contains("resume")
                .containsOnlyOnce("run_started");
    }

    @Test
    void resume前hidden被篡改则完整性熔断() throws Exception {
        // 在临时副本上做破坏性实验，绝不触碰真实任务库。
        Path taskCopy = scratch.resolve("api-payload-001");
        Dirs.copyTree(Path.of("tasks", "api-payload-001"), taskCopy);

        assertThatThrownBy(() -> RunManager.run(taskCopy, runsRoot, null, dieOnSecondAttempt(taskCopy)))
                .hasMessageContaining("模拟进程在第 2 轮被杀");
        Path runDir = findSingleRunDir(taskCopy);

        // 篡改 hidden 的期望答案。
        Files.writeString(taskCopy.resolve("hidden/expected/answer.json"),
                """
                        {"order_type": "STANDARD", "total_amount_cents": 1}
                        """, StandardCharsets.UTF_8);

        RunManager.RunOutcome outcome = RunManager.resume(runDir,
                new ScriptedAgentAdapter(taskCopy.resolve("samples/replay.yaml")));
        assertThat(outcome.status()).isEqualTo(RunStatus.INTEGRITY_BROKEN);
    }

    private Path findSingleRunDir(Path taskDir) {
        Path taskRuns = runsRoot.resolve(taskDir.getFileName().toString());
        try (var stream = Files.list(taskRuns)) {
            return stream.filter(Files::isDirectory).findFirst().orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("未找到 run 目录", e);
        }
    }
}
