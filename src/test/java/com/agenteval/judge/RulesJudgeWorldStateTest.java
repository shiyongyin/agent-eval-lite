package com.agenteval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenteval.task.TaskSpec;
import com.agenteval.testsupport.TestSpecs;
import com.agenteval.trace.TraceEventType;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code world_state} 终态比对检查的行为回归（借 tau-bench「比世界终态而非比嘴」）。
 *
 * <p>覆盖四类关键行为：正确写操作通过；「过程对、终态错」（调用合规但入参写错）被拦；
 * 伪造/未签名与失败的调用不计入终态；多重集与顺序敏感两种比对语义。
 */
class RulesJudgeWorldStateTest {

    @TempDir
    Path tempDir;

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private Path taskDir;
    private Path workspace;
    private Path traceFile;
    private Path submissionFile;

    @BeforeEach
    void setUp() throws Exception {
        taskDir = tempDir.resolve("task-x");
        Path runDir = tempDir.resolve("run");
        workspace = runDir.resolve("workspace");
        traceFile = runDir.resolve("traces/trace.jsonl");
        Files.createDirectories(taskDir.resolve("hidden"));
        Files.createDirectories(workspace);
        Files.createDirectories(traceFile.getParent());
        submissionFile = tempDir.resolve("submission.json");
        Files.writeString(submissionFile, "{}", StandardCharsets.UTF_8);
    }

    private static final String CHECKS = """
            checks:
              - id: WORLD
                type: world_state
                dimension: main
                points: 100
                blocking: true
                tools: [card.create]
                expected:
                  - {tool: card.create, input: {user_id: "u_1001", card_type: "PLATINUM"}}
                feedback_fail: 世界终态与预期不符
            """;

    private List<CheckOutcome> judge(String checksYaml) throws Exception {
        return judge(checksYaml, "attempt_001");
    }

    private List<CheckOutcome> judge(String checksYaml, String attemptId) throws Exception {
        Path rulesFile = taskDir.resolve("hidden/judge.rules.yaml");
        Files.writeString(rulesFile, """
                schema_version: 1
                judge_version: "1.0.0"
                """ + checksYaml, StandardCharsets.UTF_8);
        RulesFile rules = RulesFile.load(rulesFile, Set.of("main"));
        TaskSpec spec = TestSpecs.singleDimension("task-x");
        JudgeInput input = new JudgeInput(spec, taskDir, Jsons.json().readTree("{}"), submissionFile,
                workspace, null, traceFile, null, "run_t", attemptId, SECRET);
        return RulesJudge.run(rules, input);
    }

    private void logSignedCall(String tool, Map<String, ?> callInput, boolean success) {
        logSignedCall(tool, callInput, success, "attempt_001");
    }

    private void logSignedCall(String tool, Map<String, ?> callInput, boolean success, String attemptId) {
        TraceLogger signing = TraceLogger.open(traceFile, "run_t", SECRET);
        signing.log(TraceEventType.TOOL_CALL, attemptId, Map.of(
                "call_id", "tc_" + tool.hashCode() + "_" + System.nanoTime() % 100000,
                "tool_name", tool, "input", callInput, "success", success));
    }

    @Test
    void 正确写操作_终态一致通过() throws Exception {
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "PLATINUM"), true);

        List<CheckOutcome> outcomes = judge(CHECKS);

        assertThat(outcomes.get(0).passed()).isTrue();
        assertThat(outcomes.get(0).message()).contains("1 次写操作");
    }

    @Test
    void 过程对终态错_调用合规但入参写错_被拦() throws Exception {
        // Agent 的确真实调了写工具（过程合规），但把卡种写错——「比嘴」看不出来，比终态才拦得住。
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "STANDARD"), true);

        List<CheckOutcome> outcomes = judge(CHECKS);

        assertThat(outcomes.get(0).passed()).isFalse();
        assertThat(outcomes.get(0).message()).contains("缺少期望写操作").contains("多出非期望写操作");
        // 对外文案不得泄露期望终态。
        assertThat(outcomes.get(0).externalMessage()).doesNotContain("PLATINUM");
    }

    @Test
    void 伪造的未签名写事件_不计入终态() throws Exception {
        // Agent 直接向 trace 追加一条"看起来成功"的写事件（无合法签名）。
        String forged = "{\"event_id\":\"evt_forged\",\"run_id\":\"run_t\",\"seq\":99,"
                + "\"timestamp\":\"2026-07-07T00:00:00Z\",\"type\":\"tool_call\",\"attempt_id\":\"attempt_001\","
                + "\"payload\":{\"call_id\":\"tc_forged\",\"tool_name\":\"card.create\","
                + "\"input\":{\"user_id\":\"u_1001\",\"card_type\":\"PLATINUM\"},\"success\":true}}";
        Files.writeString(traceFile, forged + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        List<CheckOutcome> outcomes = judge(CHECKS);

        assertThat(outcomes.get(0).passed()).isFalse();
        assertThat(outcomes.get(0).message()).contains("缺少期望写操作");
    }

    @Test
    void 失败的写调用_不改变世界不计入终态() throws Exception {
        // 网关拒绝（allowlist / no_fixture）的调用没有真的写世界，不应算进终态。
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "PLATINUM"), false);

        List<CheckOutcome> outcomes = judge(CHECKS);

        assertThat(outcomes.get(0).passed()).isFalse();
        assertThat(outcomes.get(0).message()).contains("缺少期望写操作");
    }

    @Test
    void 重复写同一条_多重集语义识别为多余写操作() throws Exception {
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "PLATINUM"), true);
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "PLATINUM"), true);

        List<CheckOutcome> outcomes = judge(CHECKS);

        assertThat(outcomes.get(0).passed()).isFalse();
        assertThat(outcomes.get(0).message()).contains("多出非期望写操作");
    }

    @Test
    void 顺序敏感模式_顺序错误被拦_顺序正确通过() throws Exception {
        String ordered = """
                checks:
                  - id: WORLD
                    type: world_state
                    dimension: main
                    points: 100
                    order_sensitive: true
                    tools: [card.create]
                    expected:
                      - {tool: card.create, input: {user_id: "u_1001", card_type: "GOLD"}}
                      - {tool: card.create, input: {user_id: "u_2002", card_type: "STANDARD"}}
                """;
        logSignedCall("card.create", Map.of("user_id", "u_2002", "card_type", "STANDARD"), true);
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "GOLD"), true);
        assertThat(judge(ordered).get(0).passed()).isFalse();

        // 重置 trace 后按正确顺序写 → 通过。
        Files.deleteIfExists(traceFile);
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "GOLD"), true);
        logSignedCall("card.create", Map.of("user_id", "u_2002", "card_type", "STANDARD"), true);
        assertThat(judge(ordered).get(0).passed()).isTrue();
    }

    @Test
    void 默认attempt作用域_上轮错误写操作不毒化本轮_修正后本轮可通过() throws Exception {
        // 第 1 轮写错（反馈打回），第 2 轮写对——默认按 attempt 折叠，本轮判分只看本轮的世界。
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "STANDARD"), true, "attempt_001");
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "PLATINUM"), true, "attempt_002");

        assertThat(judge(CHECKS, "attempt_001").get(0).passed()).isFalse();
        assertThat(judge(CHECKS, "attempt_002").get(0).passed()).isTrue();
    }

    @Test
    void run作用域_折叠全部轮次的写操作() throws Exception {
        String runScoped = """
                checks:
                  - id: WORLD
                    type: world_state
                    dimension: main
                    points: 100
                    scope: run
                    tools: [card.create]
                    expected:
                      - {tool: card.create, input: {user_id: "u_1001", card_type: "PLATINUM"}}
                """;
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "STANDARD"), true, "attempt_001");
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "PLATINUM"), true, "attempt_002");

        // run 语义：世界里残留着第 1 轮的错误写操作 → 终态不符。
        List<CheckOutcome> outcomes = judge(runScoped, "attempt_002");
        assertThat(outcomes.get(0).passed()).isFalse();
        assertThat(outcomes.get(0).message()).contains("多出非期望写操作");
    }

    @Test
    void expected_from_从隐藏文件加载期望终态() throws Exception {
        Files.writeString(taskDir.resolve("hidden/world_state.final.json"), """
                [{"tool": "card.create", "input": {"user_id": "u_1001", "card_type": "PLATINUM"}}]
                """, StandardCharsets.UTF_8);
        String checks = """
                checks:
                  - id: WORLD
                    type: world_state
                    dimension: main
                    points: 100
                    tools: [card.create]
                    expected_from: world_state.final.json
                """;
        logSignedCall("card.create", Map.of("user_id", "u_1001", "card_type", "PLATINUM"), true);

        assertThat(judge(checks).get(0).passed()).isTrue();
    }

    @Test
    void 缺少tools配置_按评审设施故障抛出() {
        String broken = """
                checks:
                  - id: WORLD
                    type: world_state
                    dimension: main
                    points: 100
                    expected: []
                """;
        assertThatThrownBy(() -> judge(broken))
                .isInstanceOf(JudgeException.class)
                .hasMessageContaining("tools");
    }

    @Test
    void 非工具构成的期望_必须为数组_否则设施故障() throws Exception {
        String broken = """
                checks:
                  - id: WORLD
                    type: world_state
                    dimension: main
                    points: 100
                    tools: [card.create]
                    expected: {tool: card.create}
                """;
        logSignedCall("card.create", Map.of("user_id", "u_1001"), true);
        assertThatThrownBy(() -> judge(broken))
                .isInstanceOf(JudgeException.class)
                .hasMessageContaining("数组");
    }
}
