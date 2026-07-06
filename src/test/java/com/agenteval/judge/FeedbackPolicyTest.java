package com.agenteval.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.task.FeedbackLevel;
import com.agenteval.task.JudgeType;
import com.agenteval.task.SelectionPolicy;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskType;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FeedbackPolicy} 裁剪策略测试：各粒度回传内容边界 + private_notes 永不出闸。
 */
class FeedbackPolicyTest {

    @TempDir
    Path tempDir;

    private static TaskSpec specWithLevel(FeedbackLevel level, boolean includeScores) {
        return new TaskSpec(1, "t1", "任务", TaskType.GENERIC, "", "brief",
                List.of(), List.of(),
                new TaskSpec.Submit("json", "builtin:generic", 3, 0),
                new TaskSpec.JudgeSpec(JudgeType.RULES, "hidden/judge.rules.yaml", null, 120,
                        new TaskSpec.Feedback(level, includeScores)),
                new TaskSpec.Scoring(100, 80, SelectionPolicy.BEST_SCORE,
                        List.of(new TaskSpec.Dimension("main", 100))),
                new TaskSpec.RuntimeSpec(30, 10, true, 0, true));
    }

    private static JudgeResult sampleResult() {
        return new JudgeResult(1, "t1", "run_x", "attempt_001", "rules",
                62.5, 100, false, Map.of("main", 62.5),
                List.of("OK_RULE"),
                List.of(new JudgeResult.FailedRule("AMOUNT", "金额口径不对", "high", "main", 30, false)),
                "得分 62.5/100，未达通过线 80。改进方向：金额口径不对",
                "PRIVATE: expected=4950 actual=5500",
                new JudgeResult.Reproducibility("engine/0.1.0", "1.0.0", "h".repeat(64), "s".repeat(64),
                        "w".repeat(64), Instant.now(), true));
    }

    private JsonNode read(Path file) throws Exception {
        return Jsons.json().readTree(Files.readString(file));
    }

    @Test
    void summary级别只回分数与结论() throws Exception {
        Path file = FeedbackPolicy.writeJudged(tempDir, specWithLevel(FeedbackLevel.SUMMARY, false),
                sampleResult(), "attempt_002");
        JsonNode node = read(file);
        assertThat(node.path("score").asDouble()).isEqualTo(62.5);
        assertThat(node.path("passed").asBoolean()).isFalse();
        assertThat(node.has("failed_checks")).isFalse();
        assertThat(node.has("dimension_scores")).isFalse();
        assertThat(node.path("next_attempt_id").asText()).isEqualTo("attempt_002");
    }

    @Test
    void failedRules级别回对外文案但不含规则内部信息() throws Exception {
        Path file = FeedbackPolicy.writeJudged(tempDir, specWithLevel(FeedbackLevel.FAILED_RULES, true),
                sampleResult(), null);
        JsonNode node = read(file);
        assertThat(node.path("failed_checks")).hasSize(1);
        assertThat(node.path("failed_checks").get(0).path("message").asText()).isEqualTo("金额口径不对");
        // failed_rules 级别不回 rule_id 与失分细节。
        assertThat(node.path("failed_checks").get(0).has("rule_id")).isFalse();
        assertThat(node.path("dimension_scores").path("main").asDouble()).isEqualTo(62.5);
        assertThat(node.path("next_step").asText()).contains("次数已用尽");
    }

    @Test
    void 任何级别都不泄露privateNotes() throws Exception {
        for (FeedbackLevel level : FeedbackLevel.values()) {
            Path dir = tempDir.resolve(level.name());
            Path file = FeedbackPolicy.writeJudged(dir, specWithLevel(level, true),
                    sampleResult(), "attempt_002");
            String raw = Files.readString(file);
            assertThat(raw).as("级别 " + level + " 不得包含 private_notes")
                    .doesNotContain("PRIVATE").doesNotContain("expected=4950");
        }
    }

    @Test
    void 无效提交反馈携带schema错误() throws Exception {
        Path file = FeedbackPolicy.writeInvalid(tempDir, "attempt_001",
                List.of("$.summary: 长度不足"), "attempt_002");
        JsonNode node = read(file);
        assertThat(node.path("valid").asBoolean()).isFalse();
        assertThat(node.path("schema_errors").get(0).asText()).contains("summary");
        assertThat(node.path("next_attempt_id").asText()).isEqualTo("attempt_002");
    }
}
