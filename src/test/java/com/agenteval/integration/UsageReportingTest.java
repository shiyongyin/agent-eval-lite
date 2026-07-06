package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.agent.ManualAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 自报 usage（token/成本）链路的端到端回归：
 * 信封携带可选 {@code usage} → 校验通过 → trace 留痕（{@code usage_recorded}，签名）
 * → report.json 聚合出 cost 节点 → report.md 呈现成本段落。
 *
 * <p>口径约束：自报数据仅用于 ROI 对比，绝不参与评分——测试同时验证
 * 带 usage 与不带 usage 的提交评分一致（分数只由规则决定）。
 */
class UsageReportingTest {

    @TempDir
    Path runsRoot;

    @Test
    void 提交携带usage_留痕并聚合进报告_且不影响评分() throws Exception {
        Path taskDir = Path.of("tasks", "api-payload-001");
        String sample = Files.readString(
                taskDir.resolve("samples/attempt-pass.json"), StandardCharsets.UTF_8)
                .replace("{attempt_id}", "attempt_001")
                .replace("\"schema_version\": 1,",
                        "\"schema_version\": 1, \"usage\": {\"model\": \"test-m\", "
                                + "\"input_tokens\": 1200, \"output_tokens\": 300, \"cost_usd\": 0.015},");
        Path submission = Files.createTempFile("ael-usage-", ".json");
        try {
            Files.writeString(submission, sample, StandardCharsets.UTF_8);
            RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                    new ManualAgentAdapter(submission));

            // usage 不影响评分：pass 样例照常通过、满分。
            assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
            assertThat(outcome.bestScore()).isEqualTo(100.0);

            // trace 留痕：usage_recorded 事件带自报数据与口径标注。
            List<JsonNode> events = TraceLogger.readAll(
                    outcome.runDir().resolve("traces/trace.jsonl"));
            JsonNode usageEvent = events.stream()
                    .filter(e -> "usage_recorded".equals(e.path("type").asText()))
                    .findFirst().orElseThrow();
            assertThat(usageEvent.path("attempt_id").asText()).isEqualTo("attempt_001");
            assertThat(usageEvent.path("payload").path("input_tokens").asLong()).isEqualTo(1200);
            assertThat(usageEvent.path("payload").path("source").asText())
                    .isEqualTo("agent_self_report");
            // 框架进程写入的事件必须带签名（防 Agent 事后伪造成本）。
            assertThat(usageEvent.has(com.agenteval.trace.TraceSigner.SIG_FIELD)).isTrue();

            // report.json 聚合出 cost 节点。
            JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
            JsonNode cost = report.path("cost");
            assertThat(cost.path("reported").asBoolean()).isTrue();
            assertThat(cost.path("attempts_with_usage").asInt()).isEqualTo(1);
            assertThat(cost.path("input_tokens").asLong()).isEqualTo(1200);
            assertThat(cost.path("output_tokens").asLong()).isEqualTo(300);
            assertThat(cost.path("total_tokens").asLong()).isEqualTo(1500);
            assertThat(cost.path("cost_usd").asDouble()).isEqualTo(0.015);
            assertThat(cost.path("source").asText()).isEqualTo("agent_self_report");

            // report.md 呈现成本段落（明确标注自报、不参与评分）。
            String md = Files.readString(outcome.reportMd(), StandardCharsets.UTF_8);
            assertThat(md).contains("成本（Agent 自报，不参与评分）").contains("$0.015");
        } finally {
            Files.deleteIfExists(submission);
        }
    }

    @Test
    void 未上报usage_报告cost节点标记未上报_markdown不渲染成本段() throws Exception {
        Path taskDir = Path.of("tasks", "api-payload-001");
        String sample = Files.readString(
                taskDir.resolve("samples/attempt-pass.json"), StandardCharsets.UTF_8)
                .replace("{attempt_id}", "attempt_001");
        Path submission = Files.createTempFile("ael-nousage-", ".json");
        try {
            Files.writeString(submission, sample, StandardCharsets.UTF_8);
            RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                    new ManualAgentAdapter(submission));

            assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
            JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
            assertThat(report.path("cost").path("reported").asBoolean()).isFalse();
            assertThat(Files.readString(outcome.reportMd(), StandardCharsets.UTF_8))
                    .doesNotContain("成本（Agent 自报");
        } finally {
            Files.deleteIfExists(submission);
        }
    }

    @Test
    void usage字段非法_整份提交被信封schema拒绝() throws Exception {
        Path taskDir = Path.of("tasks", "api-payload-001");
        // cost_usd 为负、input_tokens 非整数、夹带未知字段——三类非法形态都应被拒。
        String sample = Files.readString(
                taskDir.resolve("samples/attempt-pass.json"), StandardCharsets.UTF_8)
                .replace("{attempt_id}", "attempt_001")
                .replace("\"schema_version\": 1,",
                        "\"schema_version\": 1, \"usage\": {\"cost_usd\": -1, "
                                + "\"input_tokens\": 1.5, \"vendor_secret\": \"x\"},");
        Path submission = Files.createTempFile("ael-badusage-", ".json");
        try {
            Files.writeString(submission, sample, StandardCharsets.UTF_8);
            var spec = com.agenteval.task.TaskSpecLoader.load(taskDir);
            var result = com.agenteval.submission.SubmissionManager.validate(
                    submission, spec, taskDir, "attempt_001");
            assertThat(result.valid()).isFalse();
            assertThat(String.join("; ", result.errors())).contains("usage");
        } finally {
            Files.deleteIfExists(submission);
        }
    }
}
