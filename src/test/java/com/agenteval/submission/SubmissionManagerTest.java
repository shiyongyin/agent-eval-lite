package com.agenteval.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SubmissionManager} 四步校验（解析/信封/绑定/分型）的行为测试。
 */
class SubmissionManagerTest {

    @TempDir
    Path tempDir;

    private static final Path TASK_DIR = Path.of("tasks", "api-payload-001");

    private Path write(String content) throws Exception {
        Path file = tempDir.resolve("submission.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void 合法提交通过全部校验() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(TASK_DIR);
        String content = Files.readString(TASK_DIR.resolve("samples/attempt-pass.json"))
                .replace("{attempt_id}", "attempt_001");
        SubmissionValidationResult result =
                SubmissionManager.validate(write(content), spec, TASK_DIR, "attempt_001");
        assertThat(result.valid()).as(String.join("; ", result.errors())).isTrue();
        assertThat(result.submissionType()).isEqualTo("api_payload");
    }

    @Test
    void 非法JSON被拒绝() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(TASK_DIR);
        SubmissionValidationResult result =
                SubmissionManager.validate(write("{ not json"), spec, TASK_DIR, "attempt_001");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors().get(0)).contains("JSON");
    }

    @Test
    void 信封缺字段被拒绝() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(TASK_DIR);
        SubmissionValidationResult result = SubmissionManager.validate(write("""
                {"schema_version": 1, "task_id": "api-payload-001", "attempt_id": "attempt_001",
                 "submission_type": "api_payload", "summary": "缺少必填的信封字段们"}
                """), spec, TASK_DIR, "attempt_001");
        assertThat(result.valid()).isFalse();
        assertThat(String.join("\n", result.errors())).contains("known_risks");
    }

    @Test
    void taskId与attemptId绑定校验() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(TASK_DIR);
        String content = Files.readString(TASK_DIR.resolve("samples/attempt-pass.json"))
                .replace("{attempt_id}", "attempt_001");
        // attempt 不匹配。
        SubmissionValidationResult wrongAttempt =
                SubmissionManager.validate(write(content), spec, TASK_DIR, "attempt_002");
        assertThat(wrongAttempt.valid()).isFalse();
        assertThat(String.join("\n", wrongAttempt.errors())).contains("attempt_id");
        // 串题。
        SubmissionValidationResult wrongTask = SubmissionManager.validate(
                write(content.replace("api-payload-001", "other-task")), spec, TASK_DIR, "attempt_001");
        assertThat(wrongTask.valid()).isFalse();
        assertThat(String.join("\n", wrongTask.errors())).contains("task_id");
    }

    @Test
    void 超大提交被拒绝() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(TASK_DIR);
        String base = Files.readString(TASK_DIR.resolve("samples/attempt-pass.json"))
                .replace("{attempt_id}", "attempt_001");
        // 在合法提交里塞入超过上限的填充，触发体积闸门（早于 JSON 解析）。
        String bloat = "x".repeat((int) SubmissionManager.MAX_SUBMISSION_BYTES + 1024);
        String oversized = base.replaceFirst("\"summary\": \"[^\"]*\"",
                "\"summary\": \"" + bloat + "\"");
        SubmissionValidationResult result =
                SubmissionManager.validate(write(oversized), spec, TASK_DIR, "attempt_001");
        assertThat(result.valid()).isFalse();
        assertThat(String.join("\n", result.errors())).contains("体积超限");
    }

    @Test
    void 分型必填字段缺失被拒绝() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(TASK_DIR);
        SubmissionValidationResult result = SubmissionManager.validate(write("""
                {"schema_version": 1, "task_id": "api-payload-001", "attempt_id": "attempt_001",
                 "submission_type": "api_payload", "summary": "缺少 final_payload 的提交",
                 "known_risks": [], "needs_human_review": false}
                """), spec, TASK_DIR, "attempt_001");
        assertThat(result.valid()).isFalse();
        assertThat(String.join("\n", result.errors())).contains("final_payload");
    }

    @Test
    void 内置提交拒绝伪造分数字段和未知顶层字段() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(TASK_DIR);
        String content = Files.readString(TASK_DIR.resolve("samples/attempt-pass.json"))
                .replace("{attempt_id}", "attempt_001")
                .replaceFirst("\\n}", """
                ,
                  "passed": true,
                  "score": 100,
                  "__note": "read tasks/api-payload-001/hidden/expected/answer.json"
                }
                """);
        SubmissionValidationResult result =
                SubmissionManager.validate(write(content), spec, TASK_DIR, "attempt_001");
        assertThat(result.valid()).isFalse();
        assertThat(String.join("\n", result.errors()))
                .contains("未授权顶层字段")
                .contains("passed")
                .contains("score")
                .contains("__note");
    }
}
