package com.agenteval.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.task.TaskSpec;
import com.agenteval.testsupport.TestSpecs;
import com.agenteval.trace.TraceEventType;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.agenteval.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RulesJudge} 全部检查类型的行为测试——判分引擎是框架的信任根基，逐类型覆盖。
 */
class RulesJudgeTest {

    @TempDir
    Path tempDir;

    private Path taskDir;
    private Path workspace;
    private Path submissionFile;

    @BeforeEach
    void setUp() throws Exception {
        taskDir = tempDir.resolve("task-x");
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(taskDir.resolve("hidden"));
        Files.createDirectories(workspace);
        submissionFile = tempDir.resolve("submission.json");
    }

    // ---------------------------------------------------------------- helper

    private JudgeInput input(JsonNode submission, Path baseline, Path trace) throws Exception {
        Files.writeString(submissionFile, submission.toPrettyString(), StandardCharsets.UTF_8);
        TaskSpec spec = TestSpecs.singleDimension("task-x");
        return new JudgeInput(spec, taskDir, submission, submissionFile,
                workspace, baseline, trace, null, "run_t", "attempt_001");
    }

    private List<CheckOutcome> run(String checksYaml, JsonNode submission,
                                   Path baseline, Path trace) throws Exception {
        Path rulesFile = taskDir.resolve("hidden/judge.rules.yaml");
        Files.writeString(rulesFile, """
                schema_version: 1
                judge_version: "1.0.0"
                """ + checksYaml, StandardCharsets.UTF_8);
        RulesFile rules = RulesFile.load(rulesFile, Set.of("main"));
        return RulesJudge.run(rules, input(submission, baseline, trace));
    }

    private JsonNode json(String content) throws Exception {
        return Jsons.json().readTree(content);
    }

    // ---------------------------------------------------------------- checks

    @Test
    void jsonpathEquals_内联期望与数值容差() throws Exception {
        List<CheckOutcome> outcomes = run("""
                checks:
                  - {id: EXACT, type: jsonpath_equals, dimension: main, points: 40,
                     path: "$.answer.total", expected: 4950}
                  - {id: TOLERANT, type: jsonpath_equals, dimension: main, points: 30,
                     path: "$.answer.rate", expected: 0.9, tolerance: 0.001}
                  - {id: WRONG, type: jsonpath_equals, dimension: main, points: 30,
                     path: "$.answer.total", expected: 5500,
                     feedback_fail: 金额不对}
                """,
                json("""
                        {"answer": {"total": 4950, "rate": 0.9004}}
                        """), null, null);
        assertThat(outcomes).extracting(CheckOutcome::passed).containsExactly(true, true, false);
        assertThat(outcomes.get(2).externalMessage()).isEqualTo("金额不对");
        // 对外文案绝不携带 expected 值。
        assertThat(outcomes.get(2).externalMessage()).doesNotContain("5500");
    }

    @Test
    void jsonpathEquals_支持从hidden文件取期望值() throws Exception {
        Files.writeString(taskDir.resolve("hidden/answer.json"),
                """
                        {"amount": {"total_cents": 4950}}
                        """, StandardCharsets.UTF_8);
        List<CheckOutcome> outcomes = run("""
                checks:
                  - {id: FROM_FILE, type: jsonpath_equals, dimension: main, points: 100,
                     path: "$.answer.total", expected_from: "answer.json#/amount/total_cents"}
                """,
                json("""
                        {"answer": {"total": 4950}}
                        """), null, null);
        assertThat(outcomes.get(0).passed()).isTrue();
    }

    @Test
    void listCoverage_按命中比例给部分得分() throws Exception {
        List<CheckOutcome> outcomes = run("""
                checks:
                  - id: COVER
                    type: list_coverage
                    dimension: main
                    points: 40
                    path: "$.answer.text"
                    min_matches: 3
                    expected_any_of:
                      - ["过期", "有效期"]
                      - ["并发", "锁"]
                      - ["回滚", "退款"]
                      - ["风控"]
                """,
                json("""
                        {"answer": {"text": "需要补充积分有效期规则，并发场景要加锁"}}
                        """), null, null);
        CheckOutcome outcome = outcomes.get(0);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.pointsEarned()).isEqualTo(20.0);
        assertThat(outcome.pointsPossible()).isEqualTo(40.0);
    }

    @Test
    void evidenceSources_引用越界即失败() throws Exception {
        TaskSpec spec = new TaskSpec(1, "task-x", "t", com.agenteval.task.TaskType.GENERIC, "", "brief",
                List.of("work/docs/api.md"), List.of(),
                TestSpecs.singleDimension("task-x").submit(),
                TestSpecs.singleDimension("task-x").judge(),
                TestSpecs.singleDimension("task-x").scoring(),
                TestSpecs.singleDimension("task-x").runtime());
        Path rulesFile = taskDir.resolve("hidden/judge.rules.yaml");
        Files.writeString(rulesFile, """
                schema_version: 1
                judge_version: "1.0.0"
                checks:
                  - {id: EV, type: evidence_sources_valid, dimension: main, points: 100}
                """, StandardCharsets.UTF_8);
        JsonNode bad = json("""
                {"evidence": [{"type": "file", "source": "../hidden/answer.json"}]}
                """);
        Files.writeString(submissionFile, bad.toPrettyString(), StandardCharsets.UTF_8);
        JudgeInput input = new JudgeInput(spec, taskDir, bad, submissionFile,
                workspace, null, null, null, "run_t", "attempt_001");
        List<CheckOutcome> outcomes = RulesJudge.run(RulesFile.load(rulesFile, Set.of("main")), input);
        assertThat(outcomes.get(0).passed()).isFalse();
        assertThat(outcomes.get(0).message()).contains("../hidden/answer.json");
    }

    @Test
    void changedFiles_申报未真实发生的修改被识破() throws Exception {
        Files.writeString(workspace.resolve("Main.java"), "class Main {}", StandardCharsets.UTF_8);
        Path baseline = tempDir.resolve("baseline.json");
        Jsons.json().writeValue(baseline.toFile(), WorkspaceManager.fileBaseline(workspace));

        // 未改文件却申报修改 → 失败。
        List<CheckOutcome> lying = run("""
                checks:
                  - {id: REAL, type: changed_files_verified, dimension: main, points: 100}
                """,
                json("""
                        {"changed_files": [{"path": "Main.java", "change_summary": "改了"}]}
                        """), baseline, null);
        assertThat(lying.get(0).passed()).isFalse();

        // 真改之后 → 通过。
        Files.writeString(workspace.resolve("Main.java"), "class Main { int x; }", StandardCharsets.UTF_8);
        List<CheckOutcome> honest = run("""
                checks:
                  - {id: REAL, type: changed_files_verified, dimension: main, points: 100}
                """,
                json("""
                        {"changed_files": [{"path": "Main.java", "change_summary": "改了"}]}
                        """), baseline, null);
        assertThat(honest.get(0).passed()).isTrue();
    }

    @Test
    void command_在临时副本执行且不污染真实workspace() throws Exception {
        Files.writeString(workspace.resolve("data.txt"), "original", StandardCharsets.UTF_8);
        List<CheckOutcome> outcomes = run("""
                checks:
                  - {id: CMD_OK, type: command, dimension: main, points: 50,
                     cmd: "grep -q original data.txt && echo VERIFIED && echo polluted > data.txt",
                     output_regex: "VERIFIED"}
                  - {id: CMD_FAIL, type: command, dimension: main, points: 50,
                     cmd: "exit 7"}
                """,
                json("{}"), null, null);
        assertThat(outcomes.get(0).passed()).isTrue();
        assertThat(outcomes.get(1).passed()).isFalse();
        // 命令对副本的写入不得影响真实 workspace（ephemeral 评审区语义）。
        assertThat(Files.readString(workspace.resolve("data.txt"))).isEqualTo("original");
    }

    @Test
    void toolCallRequired_核验trace中的真实调用与callId引用() throws Exception {
        Path trace = tempDir.resolve("trace.jsonl");
        TraceLogger logger = TraceLogger.open(trace, "run_t");
        logger.log(TraceEventType.TOOL_CALL, "attempt_001", Map.of(
                "call_id", "tc_real0001", "tool_name", "user.lookup", "success", true));

        // 引用真实 call_id → 通过。
        List<CheckOutcome> ok = run("""
                checks:
                  - {id: TOOL, type: tool_call_required, dimension: main, points: 100,
                     tool: user.lookup, min_calls: 1, require_referenced: true}
                """,
                json("""
                        {"tool_calls_used": [{"tool_name": "user.lookup", "call_id": "tc_real0001"}]}
                        """), null, trace);
        assertThat(ok.get(0).passed()).isTrue();

        // 编造 call_id → 识破。
        List<CheckOutcome> fabricated = run("""
                checks:
                  - {id: TOOL, type: tool_call_required, dimension: main, points: 100,
                     tool: user.lookup, min_calls: 1, require_referenced: true}
                """,
                json("""
                        {"tool_calls_used": [{"tool_name": "user.lookup", "call_id": "tc_fake"}]}
                        """), null, trace);
        assertThat(fabricated.get(0).passed()).isFalse();
        assertThat(fabricated.get(0).message()).contains("编造");
    }

    @Test
    void canary_提交或工作区出现token即判泄露() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "偷看到 SECRET-CANARY-42", StandardCharsets.UTF_8);
        Path rulesFile = taskDir.resolve("hidden/judge.rules.yaml");
        Files.writeString(rulesFile, """
                schema_version: 1
                judge_version: "1.0.0"
                canary_token: "SECRET-CANARY-42"
                checks:
                  - {id: NO_CANARY, type: no_canary_leak, dimension: main, points: 100, blocking: true}
                """, StandardCharsets.UTF_8);
        JsonNode submission = json("{}");
        List<CheckOutcome> outcomes = RulesJudge.run(
                RulesFile.load(rulesFile, Set.of("main")),
                input(submission, null, null));
        assertThat(outcomes.get(0).passed()).isFalse();
        assertThat(outcomes.get(0).message()).contains("notes.md");
    }

    @Test
    void workspace文件检查与jsonpath存在性检查() throws Exception {
        Files.writeString(workspace.resolve("out.txt"), "hello result=42", StandardCharsets.UTF_8);
        List<CheckOutcome> outcomes = run("""
                checks:
                  - {id: F_EXISTS, type: workspace_file_exists, dimension: main, points: 25, path: out.txt}
                  - {id: F_CONTAINS, type: workspace_file_contains, dimension: main, points: 25,
                     path: out.txt, pattern: "result=\\\\d+"}
                  - {id: J_EXISTS, type: jsonpath_exists, dimension: main, points: 25, path: "$.answer.x"}
                  - {id: J_MATCH, type: jsonpath_matches, dimension: main, points: 25,
                     path: "$.answer.x", regex: "^ab.*"}
                """,
                json("""
                        {"answer": {"x": "abc"}}
                        """), null, null);
        assertThat(outcomes).allMatch(CheckOutcome::passed);
    }
}
