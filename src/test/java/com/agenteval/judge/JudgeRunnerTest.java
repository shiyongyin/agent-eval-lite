package com.agenteval.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.submission.SubmissionManager;
import com.agenteval.task.JudgeType;
import com.agenteval.task.TaskSpec;
import com.agenteval.testsupport.TestSpecs;
import com.agenteval.util.Jsons;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JudgeRunner} 聚合计分模型测试：维度归一、一票否决、反馈文案与结果 schema 自检。
 */
class JudgeRunnerTest {

    @TempDir
    Path tempDir;

    private JudgeInput input(TaskSpec spec) throws Exception {
        Path taskDir = tempDir.resolve("task");
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(taskDir.resolve("hidden"));
        Files.createDirectories(workspace);
        Path submission = tempDir.resolve("sub.json");
        Files.writeString(submission, "{}", StandardCharsets.UTF_8);
        return new JudgeInput(spec, taskDir, Jsons.json().readTree("{}"), submission,
                workspace, null, null, null, "run_t", "attempt_001");
    }

    private static CheckOutcome outcome(String id, String dim, double earned, double possible,
                                        boolean passed, boolean blocking) {
        return new CheckOutcome(id, dim, earned, possible, passed, blocking, "medium", "内部", "对外");
    }

    @Test
    void 维度得分按earned除以possible乘权重聚合() throws Exception {
        TaskSpec spec = TestSpecs.withDimensions("task", List.of(
                new TaskSpec.Dimension("correctness", 60),
                new TaskSpec.Dimension("style", 40)));
        JudgeResult result = JudgeRunner.aggregate(input(spec), List.of(
                outcome("A", "correctness", 30, 30, true, false),
                outcome("B", "correctness", 0, 30, false, false),
                outcome("C", "style", 10, 10, true, false)),
                JudgeType.RULES);

        // correctness: 60 * 30/60 = 30；style: 40 * 10/10 = 40 → 70 分，未达 80。
        assertThat(result.dimensionScores()).containsEntry("correctness", 30.0);
        assertThat(result.dimensionScores()).containsEntry("style", 40.0);
        assertThat(result.score()).isEqualTo(70.0);
        assertThat(result.passed()).isFalse();
        assertThat(result.failedRules()).hasSize(1);
        assertThat(result.feedbackToAgent()).contains("未达通过线");
    }

    @Test
    void 分数达标但blocking失败仍判负() throws Exception {
        TaskSpec spec = TestSpecs.withDimensions("task", List.of(
                new TaskSpec.Dimension("correctness", 90),
                new TaskSpec.Dimension("safety", 10)));
        JudgeResult result = JudgeRunner.aggregate(input(spec), List.of(
                outcome("GOOD", "correctness", 90, 90, true, false),
                outcome("CANARY", "safety", 0, 10, false, true)),
                JudgeType.RULES);

        assertThat(result.score()).isEqualTo(90.0);
        assertThat(result.passed()).isFalse();
        assertThat(result.feedbackToAgent()).contains("一票否决");
    }

    @Test
    void judgeVersion回填进reproducibility() throws Exception {
        TaskSpec spec = TestSpecs.singleDimension("task");
        JudgeResult result = JudgeRunner.aggregate(input(spec), List.of(
                outcome("A", "main", 100, 100, true, false)),
                JudgeType.RULES, "rules-v3");
        assertThat(result.reproducibility().judgeVersion()).isEqualTo("rules-v3");
        // 兼容旧的三参重载：判分不携带版本时退化为空串，而非 null。
        JudgeResult legacy = JudgeRunner.aggregate(input(spec), List.of(
                outcome("A", "main", 100, 100, true, false)),
                JudgeType.RULES);
        assertThat(legacy.reproducibility().judgeVersion()).isEqualTo("");
    }

    @Test
    void 结果通过judge输出schema自检且指纹齐全() throws Exception {
        TaskSpec spec = TestSpecs.singleDimension("task");
        JudgeResult result = JudgeRunner.aggregate(input(spec), List.of(
                outcome("A", "main", 100, 100, true, false)),
                JudgeType.RULES);
        assertThat(result.passed()).isTrue();
        assertThat(result.reproducibility().submissionFingerprint()).hasSize(64);
        assertThat(result.reproducibility().deterministic()).isTrue();

        JsonSchema schema;
        try (var in = getClass().getResourceAsStream("/schemas/judge.result.schema.json")) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Jsons.json().readTree(in));
        }
        var node = Jsons.json().valueToTree(result);
        assertThat(SubmissionManager.validateAgainst(schema, node)).isEmpty();
    }
}
