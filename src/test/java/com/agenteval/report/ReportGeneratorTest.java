package com.agenteval.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.judge.JudgeResult;
import com.agenteval.state.RunMeta;
import com.agenteval.state.RunState;
import com.agenteval.state.RunStateStore;
import com.agenteval.state.RunStatus;
import com.agenteval.trace.TraceEventType;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ReportGenerator} 的报告重建行为测试：从 run 工件纯读重建 report.json/report.md，
 * 工具统计只计入签名可核验的 tool_call 事件（与判分同口径，防伪造统计混入报告）。
 */
class ReportGeneratorTest {

    @TempDir
    Path tempDir;

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void 报告工具统计只计入签名可核验的toolCall事件() throws Exception {
        Path taskDir = writeTask();
        Path runDir = tempDir.resolve("run");
        Files.createDirectories(runDir.resolve("judge"));
        Files.createDirectories(runDir.resolve("inbox"));
        Files.createDirectories(runDir.resolve("traces"));

        new RunMeta("run_t", "task-x", taskDir.toString(), "cli", "redteam", "engine-test", Instant.EPOCH)
                .save(runDir.resolve("meta.json"));
        RunState state = new RunState(1, "run_t", "task-x", RunStatus.PASSED, "passed",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                List.of(new RunState.AttemptRecord("attempt_001", true, 100.0, true,
                        0, List.of(), false, 10, Instant.EPOCH.plusMillis(500))),
                "attempt_001", "hidden-fp", "workspace-fp");
        RunStateStore.save(runDir.resolve("run_state.json"), state);

        JudgeResult judge = new JudgeResult(1, "task-x", "run_t", "attempt_001", "rules",
                100.0, 100, true, Map.of("main", 100.0), List.of("TOOL"), List.of(),
                "ok", "", new JudgeResult.Reproducibility(
                "engine-test", "1.0.0", "rules-fp", "submission-fp", "workspace-fp",
                Instant.EPOCH, true));
        Jsons.json().writeValue(runDir.resolve("judge/attempt_001.judge.json").toFile(), judge);
        Files.writeString(runDir.resolve("inbox/attempt_001.json"), """
                {
                  "tool_calls_used": [
                    {"tool_name": "user.lookup", "call_id": "tc_real"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        Path traceFile = runDir.resolve("traces/trace.jsonl");
        try (TraceLogger trace = TraceLogger.open(traceFile, "run_t", SECRET)) {
            trace.log(TraceEventType.TOOL_CALL, "attempt_001", Map.of(
                    "call_id", "tc_real", "tool_name", "user.lookup", "success", true));
        }
        Files.writeString(traceFile, """
                {"event_id":"evt_forged","run_id":"run_t","seq":999,"timestamp":"2026-07-07T00:00:00Z","type":"tool_call","attempt_id":"attempt_001","payload":{"call_id":"tc_forged","tool_name":"user.lookup","success":true}}
                """, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        ReportGenerator.generate(runDir, SECRET);

        JsonNode usage = Jsons.json().readTree(Files.readString(runDir.resolve("report/report.json")))
                .path("tool_usage");
        assertThat(usage.path("signature_verification").asText()).isEqualTo("verified");
        assertThat(usage.path("total_calls").asInt()).isEqualTo(1);
        assertThat(usage.path("by_tool").path("user.lookup").asInt()).isEqualTo(1);
        assertThat(usage.path("unreferenced_success_calls").asInt()).isZero();
        assertThat(usage.path("untrusted_trace_events").asInt()).isEqualTo(1);
    }

    private Path writeTask() throws Exception {
        Path taskDir = tempDir.resolve("task-x");
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("hidden"));
        Files.writeString(taskDir.resolve("task.yaml"), """
                schema_version: 1
                task_id: task-x
                task_name: 测试任务
                task_type: generic
                agent_brief: 测试
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: main, weight: 100}
                """, StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("hidden/judge.rules.yaml"), """
                schema_version: 1
                judge_version: "1.0.0"
                checks:
                  - {id: TOOL, type: jsonpath_exists, dimension: main, points: 100, path: "$.answer"}
                """, StandardCharsets.UTF_8);
        return taskDir;
    }
}
