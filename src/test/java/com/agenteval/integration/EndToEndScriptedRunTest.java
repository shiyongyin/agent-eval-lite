package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.agent.ManualAgentAdapter;
import com.agenteval.agent.ScriptedAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端回归：用脚本回放适配器完整走通「run → 收件 → 判分 → 反馈 → 多轮修正 → 报告」。
 *
 * <p>这是框架的自证测试——EdgeBench 式评估闭环的每个环节（隔离、契约、隐藏判分、
 * 受控反馈、留痕、可复现报告）都在真实目录结构上验证，不用任何 mock。
 */
class EndToEndScriptedRunTest {

    @TempDir
    Path runsRoot;

    @Test
    void api任务_首轮失败按反馈修正后第二轮通过() throws Exception {
        Path taskDir = Path.of("tasks", "api-payload-001");
        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, "test-model",
                new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_002");
        assertThat(outcome.bestScore()).isEqualTo(100.0);

        // 报告存在且内容完整。
        JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
        assertThat(report.path("run").path("status").asText()).isEqualTo("PASSED");
        assertThat(report.path("attempts")).hasSize(2);
        assertThat(report.path("score_trajectory").get(0).asDouble()).isLessThan(80);
        assertThat(report.path("score_trajectory").get(1).asDouble()).isEqualTo(100.0);
        assertThat(Files.readString(outcome.reportMd())).contains("评估报告");

        // 第一轮反馈已产出且不泄露内部信息（expected 值 4950 只存在于 hidden）。
        Path feedback1 = outcome.runDir().resolve("feedback/attempt_001.feedback.json");
        assertThat(feedback1).isRegularFile();
        String feedbackRaw = Files.readString(feedback1, StandardCharsets.UTF_8);
        assertThat(feedbackRaw).doesNotContain("4950");
        assertThat(feedbackRaw).contains("passed");

        // stop-hook 与关键生命周期事件已留痕。
        List<String> eventTypes = TraceLogger.readAll(outcome.runDir().resolve("traces/trace.jsonl"))
                .stream().map(e -> e.path("type").asText()).toList();
        assertThat(eventTypes).contains("run_started", "submission_received", "judge_completed",
                "feedback_delivered", "stop_hook_triggered", "final_selection", "run_completed");
    }

    @Test
    void 工具任务_编造callId与过程对终态错依次被识破_正确开卡后通过() throws Exception {
        Path taskDir = Path.of("tasks", "tool-call-001");
        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_003");
        assertThat(outcome.bestScore()).isEqualTo(100.0);

        // 第一轮：编造 call_id → blocking 失败。
        JsonNode judge1 = Jsons.json().readTree(Files.readString(
                outcome.runDir().resolve("judge/attempt_001.judge.json")));
        assertThat(judge1.path("passed").asBoolean()).isFalse();
        assertThat(judge1.path("failed_rules").toString()).contains("TOOL_REALLY_CALLED");

        // 第二轮：过程对、终态错——提交结论正确（CARD_TYPE_CORRECT 通过），
        // 工具也真实调用了，但实际创建的是 STANDARD 卡 → 终态比对一票否决。
        JsonNode judge2 = Jsons.json().readTree(Files.readString(
                outcome.runDir().resolve("judge/attempt_002.judge.json")));
        assertThat(judge2.path("passed").asBoolean()).isFalse();
        assertThat(judge2.path("passed_rules").toString())
                .contains("CARD_TYPE_CORRECT").contains("CARD_ACTUALLY_CREATED");
        assertThat(judge2.path("failed_rules").toString()).contains("FINAL_WORLD_STATE");

        // 第三轮正确开卡 → 通过；trace 中的真实调用被报告统计到位。
        JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
        assertThat(report.path("tool_usage").path("total_calls").asInt()).isEqualTo(4);
        assertThat(report.path("tool_usage").path("by_tool").path("user.lookup").asInt()).isEqualTo(2);
        assertThat(report.path("tool_usage").path("by_tool").path("card.create").asInt()).isEqualTo(2);
        assertThat(report.path("safety").path("canary_leaks").asLong()).isZero();
    }

    @Test
    void 代码修复任务_虚报修改被基线识破_真实修复后通过() throws Exception {
        Assumptions.assumeTrue(javacAvailable(), "环境无 javac，跳过编译类端到端用例");
        Path taskDir = Path.of("tasks", "code-fix-001");
        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_002");

        JsonNode judge1 = Jsons.json().readTree(Files.readString(
                outcome.runDir().resolve("judge/attempt_001.judge.json")));
        assertThat(judge1.path("passed").asBoolean()).isFalse();
        // 虚报修改（REAL_CHANGES）与行为规格（SPEC_BEHAVIOR）都应失败。
        assertThat(judge1.path("failed_rules").toString())
                .contains("REAL_CHANGES").contains("SPEC_BEHAVIOR");

        JsonNode judge2 = Jsons.json().readTree(Files.readString(
                outcome.runDir().resolve("judge/attempt_002.judge.json")));
        assertThat(judge2.path("passed").asBoolean()).isTrue();
        assertThat(judge2.path("score").asDouble()).isEqualTo(100.0);
    }

    @Test
    void manual单发提交_一轮判分后按耗尽结束() throws Exception {
        Path taskDir = Path.of("tasks", "prd-review-001");
        Path submission = Files.createTempFile("ael-manual-", ".json");
        try {
            Files.writeString(submission, Files.readString(
                            taskDir.resolve("samples/attempt-fail.json"), StandardCharsets.UTF_8)
                    .replace("{attempt_id}", "attempt_001"), StandardCharsets.UTF_8);
            RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                    new ManualAgentAdapter(submission));

            assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
            assertThat(outcome.bestScore()).isNotNull().isLessThan(80.0);
            JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
            assertThat(report.path("run").path("status_reason").asText()).isEqualTo("agent_exhausted");
        } finally {
            Files.deleteIfExists(submission);
        }
    }

    @Test
    void 判分可复现_同一提交离线复算得同分() throws Exception {
        Path taskDir = Path.of("tasks", "doc-analysis-001");
        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));
        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);

        // 用与 run 内判分完全相同的输入复算 attempt_002：分数必须一致（确定性承诺）。
        JsonNode online = Jsons.json().readTree(Files.readString(
                outcome.runDir().resolve("judge/attempt_002.judge.json")));
        var spec = com.agenteval.task.TaskSpecLoader.load(taskDir);
        var submissionFile = outcome.runDir().resolve("inbox/attempt_002.json");
        var validation = com.agenteval.submission.SubmissionManager.validate(
                submissionFile, spec, taskDir, null);
        assertThat(validation.valid()).isTrue();
        var offline = com.agenteval.judge.JudgeRunner.judge(new com.agenteval.judge.JudgeInput(
                spec, taskDir.toAbsolutePath(), validation.submission(), submissionFile,
                outcome.runDir().resolve("workspace"), null,
                outcome.runDir().resolve("traces/trace.jsonl"), null, "offline", "attempt_002"));
        assertThat(offline.score()).isEqualTo(online.path("score").asDouble());
        assertThat(offline.passed()).isEqualTo(online.path("passed").asBoolean());
        assertThat(offline.reproducibility().judgeRulesFingerprint())
                .isEqualTo(online.path("reproducibility").path("judge_rules_fingerprint").asText());
    }

    private static boolean javacAvailable() {
        try {
            Process process = new ProcessBuilder("javac", "-version")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
