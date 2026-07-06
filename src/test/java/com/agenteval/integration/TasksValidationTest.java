package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.judge.RulesFile;
import com.agenteval.submission.SubmissionManager;
import com.agenteval.submission.SubmissionValidationResult;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 任务库门禁测试：5 个示例任务必须始终 ①规格合法 ②规则文件合法 ③pass 样例能过提交校验。
 * 任何人改坏任务定义会在这里立刻暴露，而不是等到 run 时。
 */
class TasksValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"code-fix-001", "api-payload-001", "doc-analysis-001", "tool-call-001", "prd-review-001"})
    void 示例任务规格与规则文件合法(String taskId) {
        Path taskDir = Path.of("tasks", taskId);
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        assertThat(spec.taskId()).isEqualTo(taskId);

        RulesFile rules = RulesFile.load(
                taskDir.resolve(spec.judge().rulesFile()),
                spec.scoring().dimensions().stream()
                        .map(TaskSpec.Dimension::name)
                        .collect(Collectors.toSet()));
        assertThat(rules.checks()).isNotEmpty();
        // 每个检查项的满分点数之和按维度聚合后不得超过维度权重（允许小于：脚本类可补充）。
        spec.scoring().dimensions().forEach(dim -> {
            double possible = rules.checks().stream()
                    .filter(c -> c.dimension().equals(dim.name()))
                    .mapToDouble(RulesFile.CheckDef::points)
                    .sum();
            assertThat(possible)
                    .as("任务 %s 维度 %s 的检查点数（%s）应等于权重（%s）——Phase 1 全规则判分",
                            taskId, dim.name(), possible, dim.weight())
                    .isEqualTo(dim.weight());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"code-fix-001", "api-payload-001", "doc-analysis-001", "tool-call-001", "prd-review-001"})
    void 示例任务的pass样例通过提交校验(String taskId) throws Exception {
        Path taskDir = Path.of("tasks", taskId);
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        Path sample = taskDir.resolve("samples/attempt-pass.json");
        String content = Files.readString(sample, StandardCharsets.UTF_8)
                .replace("{attempt_id}", "attempt_001")
                .replace("{tool_call_1}", "tc_sample01");
        Path rendered = Files.createTempFile("ael-sample-", ".json");
        try {
            Files.writeString(rendered, content, StandardCharsets.UTF_8);
            SubmissionValidationResult result =
                    SubmissionManager.validate(rendered, spec, taskDir, "attempt_001");
            assertThat(result.valid())
                    .as("任务 %s 的 pass 样例应通过提交校验: %s", taskId, String.join("; ", result.errors()))
                    .isTrue();
        } finally {
            Files.deleteIfExists(rendered);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"code-fix-001", "api-payload-001", "doc-analysis-001", "tool-call-001", "prd-review-001"})
    void 示例任务附带回放脚本与失败样例(String taskId) {
        Path samples = Path.of("tasks", taskId, "samples");
        assertThat(samples.resolve("replay.yaml")).isRegularFile();
        assertThat(samples.resolve("attempt-fail.json")).isRegularFile();
        assertThat(samples.resolve("attempt-pass.json")).isRegularFile();
    }

    @SuppressWarnings("unused")
    private static Stream<String> taskIds() {
        return Stream.of("code-fix-001", "api-payload-001", "doc-analysis-001", "tool-call-001", "prd-review-001");
    }
}
