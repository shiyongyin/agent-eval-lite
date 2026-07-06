package com.agenteval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenteval.task.FeedbackLevel;
import com.agenteval.task.JudgeType;
import com.agenteval.task.SelectionPolicy;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskType;
import com.agenteval.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ScriptJudge} 的脚本契约测试：stdout JSON 解析、环境变量注入、故障即抛。
 */
class ScriptJudgeTest {

    @TempDir
    Path tempDir;

    private Path taskDir;
    private Path workspace;
    private Path submissionFile;

    @BeforeEach
    void setUp() throws Exception {
        taskDir = tempDir.resolve("task");
        workspace = tempDir.resolve("ws");
        Files.createDirectories(taskDir.resolve("hidden"));
        Files.createDirectories(workspace);
        submissionFile = tempDir.resolve("submission.json");
        Files.writeString(submissionFile, """
                {"answer": {"x": 1}}
                """, StandardCharsets.UTF_8);
    }

    private TaskSpec specWithScript(String scriptRel) {
        return new TaskSpec(1, "task", "脚本任务", TaskType.GENERIC, "", "brief",
                List.of(), List.of(),
                new TaskSpec.Submit("json", "builtin:generic", 3, 0),
                new TaskSpec.JudgeSpec(JudgeType.SCRIPT, null, scriptRel, 30,
                        new TaskSpec.Feedback(FeedbackLevel.FAILED_RULES, true)),
                new TaskSpec.Scoring(100, 80, SelectionPolicy.BEST_SCORE,
                        List.of(new TaskSpec.Dimension("main", 100))),
                new TaskSpec.RuntimeSpec(30, 10, true, 0, true));
    }

    private JudgeInput input(TaskSpec spec) throws Exception {
        return new JudgeInput(spec, taskDir, Jsons.json().readTree(Files.readString(submissionFile)),
                submissionFile, workspace, null, null, null, "run_t", "attempt_001");
    }

    @Test
    void 脚本按契约返回checks并注入环境变量() throws Exception {
        Files.writeString(taskDir.resolve("hidden/grade.sh"), """
                #!/bin/sh
                # 校验环境变量注入：提交文件必须可读。
                grep -q '"x"' "$AEL_SUBMISSION" || exit 9
                cat <<EOF
                {"checks": [
                  {"id": "S1", "dimension": "main", "points_earned": 80, "points_possible": 100,
                   "passed": false, "severity": "medium",
                   "message": "内部: 差 20 分", "external_message": "还差一点"}
                ]}
                EOF
                """, StandardCharsets.UTF_8);
        TaskSpec spec = specWithScript("hidden/grade.sh");
        List<CheckOutcome> outcomes = ScriptJudge.run(input(spec), Set.of("main"));
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).pointsEarned()).isEqualTo(80.0);
        assertThat(outcomes.get(0).externalMessage()).isEqualTo("还差一点");

        // 与聚合器串联：80/100 × 100 权重 = 80 分，达通过线。
        JudgeResult result = JudgeRunner.judge(input(spec));
        assertThat(result.score()).isEqualTo(80.0);
        assertThat(result.judgeType()).isEqualTo("script");
        assertThat(result.reproducibility().deterministic()).isFalse();
    }

    @Test
    void 脚本崩溃按评审设施故障抛出() throws Exception {
        Files.writeString(taskDir.resolve("hidden/broken.sh"), """
                #!/bin/sh
                echo "boom" >&2
                exit 3
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ScriptJudge.run(input(specWithScript("hidden/broken.sh")), Set.of("main")))
                .isInstanceOf(JudgeException.class)
                .hasMessageContaining("退出码 3");
    }

    @Test
    void 脚本输出非法JSON按故障抛出() throws Exception {
        Files.writeString(taskDir.resolve("hidden/badout.sh"), """
                #!/bin/sh
                echo "这不是 JSON"
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ScriptJudge.run(input(specWithScript("hidden/badout.sh")), Set.of("main")))
                .isInstanceOf(JudgeException.class)
                .hasMessageContaining("JSON");
    }
}
