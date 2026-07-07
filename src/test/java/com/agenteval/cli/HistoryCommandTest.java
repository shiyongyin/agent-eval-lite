package com.agenteval.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code agent-eval history} 聚合回归：跨 run 汇总 report.json，产出 (任务 × Agent) 趋势；
 * 红队攻击 run（{@code runs/redteam/} 下）不计入趋势，但其门禁摘要被并入报告。
 */
class HistoryCommandTest {

    @TempDir
    Path runsRoot;

    @Test
    void 汇总多run趋势_排除红队攻击run_并入红队门禁摘要() throws Exception {
        // api-payload-001 × scripted：两次 run，先 60 分未过、后 100 分通过 → 趋势应反映进步。
        writeRun("api-payload-001", "run_a1", "scripted", "eng/0.1.0",
                "FAILED", 60.0, 100, false, true, 0, "2026-07-06T10:00:00Z");
        writeRun("api-payload-001", "run_a2", "scripted", "eng/0.1.0",
                "PASSED", 100.0, 100, true, true, 0, "2026-07-06T11:00:00Z");
        // code-fix-001 × cli：一次 run。
        writeRun("code-fix-001", "run_c1", "cli", "eng/0.1.0",
                "FAILED", 35.0, 100, false, true, 0, "2026-07-06T12:00:00Z");
        // 红队攻击 run：位于 runs/redteam/ 下，必须被排除，不污染趋势。
        writeRun("redteam/api-payload-001", "run_rt", "cli", "eng/0.1.0",
                "PASSED", 100.0, 100, true, true, 3, "2026-07-06T13:00:00Z");
        // 红队门禁结构化报告：应被并入 history。
        writeRedteamReport();

        Path outDir = runsRoot.resolve("out");
        int code = new CommandLine(new HistoryCommand())
                .execute("--runs-root", runsRoot.toString(), "--out", outDir.toString());
        assertThat(code).isZero();

        JsonNode history = Jsons.json().readTree(
                Files.readString(outDir.resolve("history.json"), StandardCharsets.UTF_8));

        // 只统计 3 条合法 run，红队攻击 run 被排除。
        assertThat(history.path("run_count").asInt()).isEqualTo(3);
        assertThat(history.path("runs")).hasSize(3);
        for (JsonNode run : history.path("runs")) {
            assertThat(run.path("run_dir").asText()).doesNotStartWith("redteam");
        }

        // 趋势按 (task_id, agent) 分组：api-payload-001×scripted 两次，首 60 末 100，最佳 100。
        JsonNode apiTrend = trendOf(history, "api-payload-001", "scripted");
        assertThat(apiTrend.path("count").asInt()).isEqualTo(2);
        assertThat(apiTrend.path("pass_count").asInt()).isEqualTo(1);
        assertThat(apiTrend.path("pass_rate").asDouble()).isEqualTo(0.5);
        assertThat(apiTrend.path("first_score").asDouble()).isEqualTo(60.0);
        assertThat(apiTrend.path("last_score").asDouble()).isEqualTo(100.0);
        assertThat(apiTrend.path("best_score").asDouble()).isEqualTo(100.0);

        JsonNode fixTrend = trendOf(history, "code-fix-001", "cli");
        assertThat(fixTrend.path("count").asInt()).isEqualTo(1);
        assertThat(fixTrend.path("pass_rate").asDouble()).isEqualTo(0.0);

        // 红队门禁摘要被并入。
        assertThat(history.path("redteam").path("gate").asText()).isEqualTo("pass");
        assertThat(history.path("redteam").path("counts").path("defended").asInt()).isEqualTo(16);

        assertThat(outDir.resolve("history.md")).isRegularFile();
    }

    @Test
    void 空runs目录返回错误码() {
        int code = new CommandLine(new HistoryCommand())
                .execute("--runs-root", runsRoot.toString());
        assertThat(code).isEqualTo(1);
    }

    @Test
    void 按任务与agent过滤() throws Exception {
        writeRun("api-payload-001", "run_a1", "scripted", "eng/0.1.0",
                "PASSED", 100.0, 100, true, true, 0, "2026-07-06T10:00:00Z");
        writeRun("code-fix-001", "run_c1", "cli", "eng/0.1.0",
                "FAILED", 35.0, 100, false, true, 0, "2026-07-06T12:00:00Z");

        Path outDir = runsRoot.resolve("out");
        int code = new CommandLine(new HistoryCommand()).execute(
                "--runs-root", runsRoot.toString(), "--out", outDir.toString(),
                "--task", "api-payload-001");
        assertThat(code).isZero();

        JsonNode history = Jsons.json().readTree(
                Files.readString(outDir.resolve("history.json"), StandardCharsets.UTF_8));
        assertThat(history.path("run_count").asInt()).isEqualTo(1);
        assertThat(history.path("runs").get(0).path("task_id").asText()).isEqualTo("api-payload-001");
    }

    // ---------------------------------------------------------------- fixtures

    private static JsonNode trendOf(JsonNode history, String taskId, String agent) {
        for (JsonNode t : history.path("trends")) {
            if (taskId.equals(t.path("task_id").asText()) && agent.equals(t.path("agent").asText())) {
                return t;
            }
        }
        throw new AssertionError("未找到趋势 " + taskId + " × " + agent);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void writeRun(String taskRel, String runId, String agent, String engineVersion,
                          String status, double score, int maxScore, boolean passed,
                          boolean deterministic, long canaryLeaks, String startedAt) throws Exception {
        var root = Jsons.json().createObjectNode();
        root.put("schema_version", 1);
        var run = root.putObject("run");
        run.put("run_id", runId);
        run.put("task_id", taskRel.contains("/") ? taskRel.substring(taskRel.indexOf('/') + 1) : taskRel);
        run.put("agent", agent);
        run.put("model", "m");
        run.put("engine_version", engineVersion);
        run.put("status", status);
        run.put("started_at", startedAt);
        run.put("duration_ms", 100);
        var best = root.putObject("best_attempt");
        best.put("score", score);
        best.put("max_score", maxScore);
        best.put("passed", passed);
        root.putObject("safety").put("canary_leaks", canaryLeaks);
        root.putObject("reproducibility").putObject("best_attempt_judge")
                .put("deterministic", deterministic);

        Path reportDir = runsRoot.resolve(taskRel).resolve(runId).resolve("report");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("report.json"), root.toPrettyString(), StandardCharsets.UTF_8);
    }

    private void writeRedteamReport() throws Exception {
        Path redteamDir = runsRoot.resolve("redteam");
        Files.createDirectories(redteamDir);
        Files.writeString(redteamDir.resolve("redteam_report.json"), """
                {
                  "generated_at": "2026-07-06T13:30:00+00:00",
                  "counts": {"defended": 16, "vulnerable": 0, "infra": 0, "check": 0},
                  "allowed_vulnerable_baseline": 0,
                  "gate": "pass"
                }
                """, StandardCharsets.UTF_8);
    }
}
