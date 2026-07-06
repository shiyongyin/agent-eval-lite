package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.agent.AgentAdapter;
import com.agenteval.agent.AttemptInput;
import com.agenteval.agent.AttemptOutcome;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * auto-eval 后台采样端到端回归：用一个「先交草稿、再慢慢完成」的慢 Agent 驱动真实 run，
 * 验证设计 §10 的 Phase 3 语义——按间隔快照评审、轨迹进 trace 与 report、
 * <strong>绝不回注 Agent</strong>、绝不影响正式成绩。
 */
class AutoEvalSamplingTest {

    @TempDir
    Path tempRoot;

    /**
     * 慢 Agent：立刻写一份错误草稿到 inbox，随后指定时长内“继续工作”，最后写入正确提交。
     * 采样窗口内 inbox 始终有内容，采样分应反映草稿的低分。
     */
    private static final class SlowDraftingAgent implements AgentAdapter {

        private final String draft;
        private final String finals;
        private final long workMillis;

        SlowDraftingAgent(String draft, String finals, long workMillis) {
            this.draft = draft;
            this.finals = finals;
            this.workMillis = workMillis;
        }

        @Override
        public String name() {
            return "slow-drafting";
        }

        @Override
        public AttemptOutcome runAttempt(AttemptInput input) {
            Path inboxFile = input.expectedSubmissionFile();
            try {
                Files.createDirectories(inboxFile.getParent());
                Files.writeString(inboxFile, fill(draft, input.attemptId()), StandardCharsets.UTF_8);
                Thread.sleep(workMillis);
                Files.writeString(inboxFile, fill(finals, input.attemptId()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AttemptOutcome(inboxFile, true, 0, null, false);
        }

        private static String fill(String template, String attemptId) {
            return template.replace("{attempt_id}", attemptId);
        }
    }

    @Test
    void 采样轨迹进trace与report_不回注Agent_不影响正式成绩() throws Exception {
        Path taskDir = autoEvalTask("auto-eval-001", 1);
        String draft = """
                {"schema_version":1,"task_id":"auto-eval-001","attempt_id":"{attempt_id}",
                 "submission_type":"generic","summary":"草稿：先占位再继续算",
                 "known_risks":[],"needs_human_review":false,
                 "answer":{"value":-1}}
                """;
        String finals = draft.replace("\"value\":-1", "\"value\":42");

        RunManager.RunOutcome outcome = RunManager.run(taskDir, tempRoot.resolve("runs"), null,
                new SlowDraftingAgent(draft, finals, 2600));

        // 正式成绩不受采样影响：最终提交正确 → PASSED 满分。
        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestScore()).isEqualTo(100.0);

        // trace 中留下 ≥2 个采样事件（2.6s 工作窗口 / 1s 间隔），采样分是草稿的低分。
        List<JsonNode> samples = TraceLogger.readAll(outcome.runDir().resolve("traces/trace.jsonl"))
                .stream()
                .filter(e -> "auto_eval_sampled".equals(e.path("type").asText()))
                .toList();
        assertThat(samples).hasSizeGreaterThanOrEqualTo(2);
        for (JsonNode sample : samples) {
            assertThat(sample.path("attempt_id").asText()).isEqualTo("attempt_001");
            assertThat(sample.path("payload").path("kind").asText()).isEqualTo("auto");
            assertThat(sample.path("payload").path("score").asDouble()).isLessThan(80.0);
            assertThat(sample.path("payload").path("has_submission").asBoolean()).isTrue();
        }

        // report 单列采样轨迹（enabled + samples），并与正式 score_trajectory 分开。
        JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
        assertThat(report.path("auto_eval").path("enabled").asBoolean()).isTrue();
        assertThat(report.path("auto_eval").path("sample_count").asInt())
                .isEqualTo(samples.size());
        assertThat(report.path("auto_eval").path("samples").get(0).path("score").asDouble())
                .isLessThan(80.0);
        assertThat(report.path("score_trajectory")).hasSize(1);
        assertThat(Files.readString(outcome.reportMd())).contains("Auto-eval 采样轨迹");

        // 不回注 Agent：feedback 目录里只有正式判分产生的反馈，没有任何采样反馈。
        try (var files = Files.list(outcome.runDir().resolve("feedback"))) {
            assertThat(files.filter(f -> f.getFileName().toString().contains("sample"))).isEmpty();
        }
        // 采样评审产物隔离在 judge/auto/（Agent 禁区），不污染正式判分文件。
        assertThat(outcome.runDir().resolve("judge/attempt_001.judge.json")).isRegularFile();

        // 采样事件带合法签名（框架进程代写）→ 报告统计口径为 verified。
        assertThat(report.path("tool_usage").path("signature_verification").asText())
                .isEqualTo("verified");
    }

    @Test
    void 间隔为零_零采样零事件_行为与既有链路完全一致() throws Exception {
        Path taskDir = autoEvalTask("auto-eval-002", 0);
        String finals = """
                {"schema_version":1,"task_id":"auto-eval-002","attempt_id":"{attempt_id}",
                 "submission_type":"generic","summary":"一次到位的正确提交",
                 "known_risks":[],"needs_human_review":false,
                 "answer":{"value":42}}
                """;

        RunManager.RunOutcome outcome = RunManager.run(taskDir, tempRoot.resolve("runs"), null,
                new SlowDraftingAgent(finals, finals, 50));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        List<JsonNode> samples = TraceLogger.readAll(outcome.runDir().resolve("traces/trace.jsonl"))
                .stream()
                .filter(e -> "auto_eval_sampled".equals(e.path("type").asText()))
                .toList();
        assertThat(samples).isEmpty();
        JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
        assertThat(report.path("auto_eval").path("enabled").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- fixture

    private Path autoEvalTask(String taskId, int intervalSeconds) throws IOException {
        Path taskDir = tempRoot.resolve(taskId);
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("hidden"));
        Files.writeString(taskDir.resolve("work/notes.md"), "answer.value 应为 42\n", StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("task.yaml"), """
                schema_version: 1
                task_id: %s
                task_name: auto-eval 采样测试任务
                task_type: generic
                agent_brief: 提交 answer.value=42
                submit:
                  max_attempts: 2
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - name: correctness
                      weight: 100
                runtime:
                  timeout_minutes: 5
                  attempt_timeout_minutes: 2
                  auto_eval_interval_seconds: %d
                """.formatted(taskId, intervalSeconds), StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("hidden/judge.rules.yaml"), """
                schema_version: 1
                judge_version: v1
                checks:
                  - id: ANSWER_CORRECT
                    type: jsonpath_equals
                    dimension: correctness
                    points: 10
                    path: $.answer.value
                    expected: 42
                    feedback_fail: answer.value 不正确
                """, StandardCharsets.UTF_8);
        return taskDir;
    }
}
