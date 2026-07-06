package com.agenteval.report;

import com.agenteval.state.RunMeta;
import com.agenteval.state.RunState;
import com.agenteval.state.RunStateStore;
import com.agenteval.state.RunStatus;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import com.agenteval.trace.TraceLogger;
import com.agenteval.trace.TraceSecret;
import com.agenteval.trace.TraceSigner;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 报告生成器：从 run 目录的既有工件（meta / run_state / judge / trace / inbox）
 * 纯读地重建评估报告，输出机器可读的 {@code report.json} 与人类可读的 {@code report.md}。
 *
 * <p>纯读 + 幂等：{@code agent-eval report --run <dir>} 可在任何时刻重跑而不改变评估事实——
 * 报告是事实的<em>视图</em>，不是事实本身。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class ReportGenerator {

    private ReportGenerator() {
    }

    /**
     * 报告产物路径组。
     *
     * @param reportJson report.json
     * @param reportMd report.md
     */
    public record Paths(Path reportJson, Path reportMd) {
    }

    /**
     * 生成（或重建）报告。
     *
     * @param runDir run 目录
     * @return 报告路径组
     */
    public static Paths generate(Path runDir) {
        return generate(runDir, TraceSecret.load(runDir));
    }

    /**
     * 生成（或重建）报告，并使用调用方提供的 trace 密钥核验工具调用事件。
     *
     * <p>run 收尾阶段密钥尚未落盘，因此 Runner 必须传入内存密钥；离线重建则走
     * {@link #generate(Path)} 从 run 目录加载密钥。
     *
     * @param runDir run 目录
     * @param traceSecret trace 签名密钥；为 {@code null} 时无法核验，保留历史统计口径
     * @return 报告路径组
     */
    public static Paths generate(Path runDir, byte[] traceSecret) {
        RunMeta meta = RunMeta.load(runDir.resolve("meta.json"));
        RunState state = RunStateStore.load(runDir.resolve("run_state.json"));
        if (state == null) {
            throw new IllegalStateException("run_state.json 不存在: " + runDir);
        }
        TaskSpec spec = TaskSpecLoader.load(Path.of(meta.taskDir()));
        List<JsonNode> traceEvents = TraceLogger.readAll(runDir.resolve("traces/trace.jsonl"));
        Optional<RunState.AttemptRecord> best = state.bestAttemptId() == null
                ? BestAttemptSelector.select(state.attempts(), spec.scoring().selection())
                : state.attempts().stream()
                        .filter(a -> a.attemptId().equals(state.bestAttemptId()))
                        .findFirst();
        JsonNode bestJudge = best.map(a -> readJudgeJson(runDir, a.attemptId())).orElse(null);
        JsonNode bestSubmission = best.map(a -> readSubmission(runDir, a.attemptId())).orElse(null);

        ObjectNode report = buildJson(meta, state, spec, best.orElse(null), bestJudge,
                bestSubmission, traceEvents, traceSecret);
        String markdown = renderMarkdown(report);

        try {
            Path reportDir = runDir.resolve("report");
            Files.createDirectories(reportDir);
            Path jsonFile = reportDir.resolve("report.json");
            Path mdFile = reportDir.resolve("report.md");
            Files.writeString(jsonFile, report.toPrettyString(), StandardCharsets.UTF_8);
            Files.writeString(mdFile, markdown, StandardCharsets.UTF_8);
            return new Paths(jsonFile, mdFile);
        } catch (IOException e) {
            throw new UncheckedIOException("写入报告失败", e);
        }
    }

    // ---------------------------------------------------------------- json

    private static ObjectNode buildJson(RunMeta meta, RunState state, TaskSpec spec,
                                        RunState.AttemptRecord best, JsonNode bestJudge,
                                        JsonNode bestSubmission, List<JsonNode> traceEvents,
                                        byte[] traceSecret) {
        ObjectNode root = Jsons.json().createObjectNode();
        root.put("schema_version", 1);

        ObjectNode run = root.putObject("run");
        run.put("run_id", meta.runId());
        run.put("task_id", meta.taskId());
        run.put("task_name", spec.taskName());
        run.put("task_type", spec.taskType().jsonName());
        run.put("agent", meta.agentName());
        run.put("model", meta.modelName());
        run.put("engine_version", meta.engineVersion());
        run.put("started_at", state.startedAt().toString());
        run.put("finished_at", state.updatedAt().toString());
        run.put("duration_ms", Duration.between(state.startedAt(), state.updatedAt()).toMillis());
        run.put("status", state.status().name());
        run.put("status_reason", state.statusReason() == null ? "" : state.statusReason());

        ArrayNode attempts = root.putArray("attempts");
        ArrayNode trajectory = root.putArray("score_trajectory");
        int invalidCount = 0;
        Map<String, Integer> failuresByRule = new TreeMap<>();
        for (RunState.AttemptRecord record : state.attempts()) {
            ObjectNode node = attempts.addObject();
            node.put("attempt_id", record.attemptId());
            node.put("valid", record.valid());
            if (record.score() == null) {
                node.putNull("score");
                trajectory.addNull();
            } else {
                node.put("score", record.score());
                trajectory.add(record.score());
            }
            node.put("passed", record.passed());
            node.put("blocking_failures", record.blockingFailures());
            node.set("failed_rule_ids", Jsons.json().valueToTree(record.failedRuleIds()));
            node.put("needs_human_review", record.needsHumanReview());
            node.put("duration_ms", record.durationMs());
            if (!record.valid()) {
                invalidCount++;
            }
            for (String ruleId : record.failedRuleIds()) {
                failuresByRule.merge(ruleId, 1, Integer::sum);
            }
        }

        if (best != null && bestJudge != null) {
            ObjectNode bestNode = root.putObject("best_attempt");
            bestNode.put("attempt_id", best.attemptId());
            bestNode.put("score", best.score());
            bestNode.put("max_score", spec.scoring().maxScore());
            bestNode.put("passed", best.passed());
            bestNode.put("selection_policy", spec.scoring().selection().jsonName());
            ArrayNode dims = bestNode.putArray("dimension_breakdown");
            JsonNode dimensionScores = bestJudge.path("dimension_scores");
            for (TaskSpec.Dimension dim : spec.scoring().dimensions()) {
                ObjectNode item = dims.addObject();
                item.put("dimension", dim.name());
                item.put("earned", dimensionScores.path(dim.name()).asDouble(0));
                item.put("max", dim.weight());
            }
            bestNode.set("failed_rules", bestJudge.path("failed_rules"));
        } else {
            root.putNull("best_attempt");
        }

        ObjectNode failureStats = root.putObject("failure_stats");
        failureStats.put("invalid_submissions", invalidCount);
        failureStats.set("by_rule", Jsons.json().valueToTree(failuresByRule));

        root.set("tool_usage", buildToolUsage(traceEvents, bestSubmission, traceSecret));
        root.set("cost", buildCost(traceEvents, traceSecret));
        root.set("auto_eval", buildAutoEval(traceEvents, traceSecret));

        ObjectNode safety = root.putObject("safety");
        long canaryLeaks = state.attempts().stream()
                .flatMap(a -> a.failedRuleIds().stream())
                .filter(id -> id.toLowerCase(Locale.ROOT).contains("canary"))
                .count();
        safety.put("canary_leaks", canaryLeaks);
        safety.put("hidden_integrity", state.status() == RunStatus.INTEGRITY_BROKEN ? "broken" : "ok");
        safety.put("needs_human_review", state.attempts().stream()
                .anyMatch(RunState.AttemptRecord::needsHumanReview));

        ObjectNode repro = root.putObject("reproducibility");
        repro.put("engine_version", meta.engineVersion());
        // judge_version 取自最佳 attempt 的判分结果（纯脚本评审时为空串）；无有效提交时留空。
        repro.put("judge_version", bestJudge == null ? ""
                : bestJudge.path("reproducibility").path("judge_version").asText(""));
        repro.put("hidden_fingerprint", state.hiddenFingerprintBefore());
        repro.put("workspace_fingerprint_initial", state.workspaceFingerprintInitial());
        if (bestJudge != null) {
            repro.set("best_attempt_judge", bestJudge.path("reproducibility"));
        }

        ObjectNode artifacts = root.putObject("artifacts");
        artifacts.put("trace", "traces/trace.jsonl");
        artifacts.put("judge_dir", "judge/");
        artifacts.put("inbox_dir", "inbox/");
        artifacts.put("feedback_dir", "feedback/");
        artifacts.put("instructions", "instructions.md");
        return root;
    }

    private static ObjectNode buildToolUsage(List<JsonNode> traceEvents, JsonNode bestSubmission, byte[] traceSecret) {
        ObjectNode usage = Jsons.json().createObjectNode();
        Map<String, Integer> byTool = new TreeMap<>();
        int total = 0;
        int failed = 0;
        int untrusted = 0;
        Set<String> successCallIds = new HashSet<>();
        for (JsonNode event : traceEvents) {
            if (!"tool_call".equals(event.path("type").asText())) {
                continue;
            }
            if (traceSecret != null && !TraceSigner.verify(traceSecret, event)) {
                untrusted++;
                continue;
            }
            total++;
            JsonNode payload = event.path("payload");
            byTool.merge(payload.path("tool_name").asText(), 1, Integer::sum);
            if (payload.path("success").asBoolean(false)) {
                successCallIds.add(payload.path("call_id").asText());
            } else {
                failed++;
            }
        }
        Set<String> referenced = new HashSet<>();
        if (bestSubmission != null) {
            bestSubmission.path("tool_calls_used")
                    .forEach(item -> referenced.add(item.path("call_id").asText()));
        }
        Set<String> unreferenced = new HashSet<>(successCallIds);
        unreferenced.removeAll(referenced);

        usage.put("total_calls", total);
        usage.set("by_tool", Jsons.json().valueToTree(new LinkedHashMap<>(byTool)));
        usage.put("failed_calls", failed);
        usage.put("unreferenced_success_calls", unreferenced.size());
        usage.put("untrusted_trace_events", untrusted);
        usage.put("signature_verification", traceSecret == null ? "unavailable" : "verified");
        return usage;
    }

    /**
     * 聚合 Agent 自报的 token/成本消耗（trace 中的 {@code usage_recorded} 事件）。
     *
     * <p>自报口径：数字由 Agent 提供、框架只负责留痕与汇总，不参与评分——
     * 报告中恒久标注 {@code source=agent_self_report}，避免被误读为可信计量。
     * 有签名密钥时只统计签名可核验的事件（防 Agent 事后伪造低成本假象）。
     */
    private static ObjectNode buildCost(List<JsonNode> traceEvents, byte[] traceSecret) {
        ObjectNode cost = Jsons.json().createObjectNode();
        long inputTokens = 0;
        long outputTokens = 0;
        double costUsd = 0;
        int attemptsWithUsage = 0;
        ArrayNode byAttempt = Jsons.json().createArrayNode();
        for (JsonNode event : traceEvents) {
            if (!"usage_recorded".equals(event.path("type").asText())) {
                continue;
            }
            if (traceSecret != null && !TraceSigner.verify(traceSecret, event)) {
                continue;
            }
            JsonNode payload = event.path("payload");
            attemptsWithUsage++;
            inputTokens += payload.path("input_tokens").asLong(0);
            outputTokens += payload.path("output_tokens").asLong(0);
            costUsd += payload.path("cost_usd").asDouble(0);
            ObjectNode item = byAttempt.addObject();
            item.put("attempt_id", event.path("attempt_id").asText());
            item.setAll((ObjectNode) payload.deepCopy());
        }
        cost.put("reported", attemptsWithUsage > 0);
        cost.put("source", "agent_self_report");
        cost.put("attempts_with_usage", attemptsWithUsage);
        cost.put("input_tokens", inputTokens);
        cost.put("output_tokens", outputTokens);
        cost.put("total_tokens", inputTokens + outputTokens);
        cost.put("cost_usd", Math.round(costUsd * 1_000_000) / 1_000_000.0);
        cost.set("by_attempt", byAttempt);
        return cost;
    }

    /**
     * 还原 auto-eval 后台采样轨迹（trace 中的 {@code auto_eval_sampled} 事件）。
     *
     * <p>采样分只是「进行中的进度信号」，与正式判分（judge_completed）分属不同口径，
     * 报告中单列且不参与任何成绩计算。有签名密钥时只统计可核验事件。
     */
    private static ObjectNode buildAutoEval(List<JsonNode> traceEvents, byte[] traceSecret) {
        ObjectNode autoEval = Jsons.json().createObjectNode();
        ArrayNode samples = Jsons.json().createArrayNode();
        for (JsonNode event : traceEvents) {
            if (!"auto_eval_sampled".equals(event.path("type").asText())) {
                continue;
            }
            if (traceSecret != null && !TraceSigner.verify(traceSecret, event)) {
                continue;
            }
            JsonNode payload = event.path("payload");
            ObjectNode item = samples.addObject();
            item.put("attempt_id", event.path("attempt_id").asText());
            item.put("timestamp", event.path("timestamp").asText());
            if (payload.has("error")) {
                item.put("error", payload.path("error").asText());
            } else {
                item.put("score", payload.path("score").asDouble());
                item.put("passed", payload.path("passed").asBoolean(false));
                item.put("has_submission", payload.path("has_submission").asBoolean(false));
            }
        }
        autoEval.put("enabled", !samples.isEmpty());
        autoEval.put("sample_count", samples.size());
        autoEval.set("samples", samples);
        return autoEval;
    }

    // ---------------------------------------------------------------- markdown

    private static String renderMarkdown(ObjectNode report) {
        JsonNode run = report.path("run");
        StringBuilder sb = new StringBuilder();
        sb.append("# 评估报告：").append(run.path("task_name").asText()).append("\n\n");

        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append(row("run_id", code(run.path("run_id").asText())));
        sb.append(row("任务", code(run.path("task_id").asText())
                + "（" + run.path("task_type").asText() + "）"));
        sb.append(row("Agent / 模型", run.path("agent").asText()
                + (run.path("model").asText().isBlank() ? "" : " / " + run.path("model").asText())));
        sb.append(row("状态", "**" + run.path("status").asText() + "**"
                + (run.path("status_reason").asText().isBlank() ? "" : "（" + run.path("status_reason").asText() + "）")));
        sb.append(row("耗时", run.path("duration_ms").asLong() + " ms"));
        sb.append(row("引擎", run.path("engine_version").asText()));
        sb.append("\n");

        JsonNode best = report.path("best_attempt");
        if (!best.isNull() && !best.isMissingNode()) {
            sb.append("## 最终成绩（").append(best.path("selection_policy").asText()).append("）\n\n");
            sb.append("**").append(best.path("score").asDouble())
                    .append(" / ").append(best.path("max_score").asInt())
                    .append(best.path("passed").asBoolean() ? "，通过**" : "，未通过**")
                    .append("（最佳轮次：").append(best.path("attempt_id").asText()).append("）\n\n");
            sb.append("| 维度 | 得分 | 满分 |\n|---|---|---|\n");
            for (JsonNode dim : best.path("dimension_breakdown")) {
                sb.append("| ").append(dim.path("dimension").asText())
                        .append(" | ").append(dim.path("earned").asDouble())
                        .append(" | ").append(dim.path("max").asInt()).append(" |\n");
            }
            sb.append("\n");
            if (!best.path("failed_rules").isEmpty()) {
                sb.append("### 最佳轮次仍失败的检查\n\n");
                sb.append("| 检查 | 维度 | 严重度 | 失分 | 一票否决 | 说明 |\n|---|---|---|---|---|---|\n");
                for (JsonNode rule : best.path("failed_rules")) {
                    sb.append("| ").append(code(rule.path("rule_id").asText()))
                            .append(" | ").append(rule.path("dimension").asText())
                            .append(" | ").append(rule.path("severity").asText())
                            .append(" | ").append(rule.path("points_lost").asDouble())
                            .append(" | ").append(rule.path("blocking").asBoolean() ? "是" : "否")
                            .append(" | ").append(rule.path("message").asText()).append(" |\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("## 最终成绩\n\n无有效提交，未产生成绩。\n\n");
        }

        sb.append("## 轮次轨迹\n\n");
        sb.append("| 轮次 | 有效 | 得分 | 通过 | 失败检查 | 耗时(ms) |\n|---|---|---|---|---|---|\n");
        for (JsonNode attempt : report.path("attempts")) {
            sb.append("| ").append(attempt.path("attempt_id").asText())
                    .append(" | ").append(attempt.path("valid").asBoolean() ? "是" : "否")
                    .append(" | ").append(attempt.path("score").isNull() ? "—" : attempt.path("score").asDouble())
                    .append(" | ").append(attempt.path("passed").asBoolean() ? "是" : "否")
                    .append(" | ").append(joinRuleIds(attempt.path("failed_rule_ids")))
                    .append(" | ").append(attempt.path("duration_ms").asLong()).append(" |\n");
        }
        sb.append("\n");

        JsonNode usage = report.path("tool_usage");
        sb.append("## 工具使用\n\n");
        if (usage.path("total_calls").asInt() == 0) {
            sb.append("本次 run 未发生网关工具调用。\n\n");
        } else {
            sb.append("- 总调用：").append(usage.path("total_calls").asInt())
                    .append("（失败 ").append(usage.path("failed_calls").asInt())
                    .append("，成功但未被提交引用 ").append(usage.path("unreferenced_success_calls").asInt())
                    .append("）\n- 按工具：").append(usage.path("by_tool").toString()).append("\n\n");
            if (usage.path("untrusted_trace_events").asInt() > 0) {
                sb.append("- 未可信 trace 事件：**").append(usage.path("untrusted_trace_events").asInt())
                        .append("**（未计入工具使用）\n\n");
            }
        }

        JsonNode autoEval = report.path("auto_eval");
        if (autoEval.path("enabled").asBoolean(false)) {
            sb.append("## Auto-eval 采样轨迹（进行中快照，不参与成绩）\n\n");
            sb.append("| 轮次 | 时间 | 采样分 | 已有提交 |\n|---|---|---|---|\n");
            for (JsonNode sample : autoEval.path("samples")) {
                sb.append("| ").append(sample.path("attempt_id").asText())
                        .append(" | ").append(sample.path("timestamp").asText())
                        .append(" | ").append(sample.has("error")
                                ? "采样失败" : String.valueOf(sample.path("score").asDouble()))
                        .append(" | ").append(sample.path("has_submission").asBoolean(false) ? "是" : "否")
                        .append(" |\n");
            }
            sb.append("\n");
        }

        JsonNode cost = report.path("cost");
        if (cost.path("reported").asBoolean(false)) {
            sb.append("## 成本（Agent 自报，不参与评分）\n\n");
            sb.append("- token：输入 ").append(cost.path("input_tokens").asLong())
                    .append(" / 输出 ").append(cost.path("output_tokens").asLong())
                    .append("（合计 ").append(cost.path("total_tokens").asLong()).append("）\n");
            sb.append("- 成本：$").append(cost.path("cost_usd").asDouble())
                    .append("（").append(cost.path("attempts_with_usage").asInt())
                    .append(" 轮上报，口径 ").append(cost.path("source").asText()).append("）\n\n");
        }

        JsonNode safety = report.path("safety");
        sb.append("## 安全与完整性\n\n");
        sb.append("- hidden 完整性：").append("ok".equals(safety.path("hidden_integrity").asText()) ? "完好" : "**已破坏（结果不可信）**").append("\n");
        sb.append("- canary 泄露：").append(safety.path("canary_leaks").asLong() == 0 ? "无" : "**" + safety.path("canary_leaks").asLong() + " 次**").append("\n");
        sb.append("- 人工复核请求：").append(safety.path("needs_human_review").asBoolean() ? "有" : "无").append("\n\n");

        JsonNode repro = report.path("reproducibility");
        sb.append("## 可复现性\n\n");
        sb.append("- 引擎：").append(code(repro.path("engine_version").asText())).append("\n");
        sb.append("- 规则版本：").append(code(repro.path("judge_version").asText().isBlank()
                ? "—" : repro.path("judge_version").asText())).append("\n");
        sb.append("- hidden 指纹：").append(code(shortHash(repro.path("hidden_fingerprint").asText()))).append("\n");
        sb.append("- workspace 初始指纹：").append(code(shortHash(repro.path("workspace_fingerprint_initial").asText()))).append("\n");
        sb.append("\n复核方式：`agent-eval judge --task <任务目录> --submission inbox/<attempt>.json`（相同输入必得相同分数）。\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- helper

    private static JsonNode readJudgeJson(Path runDir, String attemptId) {
        Path file = runDir.resolve("judge").resolve(attemptId + ".judge.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Jsons.json().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取评分结果失败: " + file, e);
        }
    }

    private static JsonNode readSubmission(Path runDir, String attemptId) {
        Path file = runDir.resolve("inbox").resolve(attemptId + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Jsons.json().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private static String row(String key, String value) {
        return "| " + key + " | " + value + " |\n";
    }

    private static String code(String value) {
        return "`" + value + "`";
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12);
    }

    private static String joinRuleIds(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode id : array) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(code(id.asText()));
        }
        return sb.toString();
    }
}
