package com.agenteval.cli;

import com.agenteval.util.Ids;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval history}：汇总 {@code runs/} 下历次评估的 {@code report.json}，
 * 产出「跨 run / 跨 Agent / 跨版本」的趋势报告（JSON + Markdown）。
 *
 * <p>定位是<strong>结果治理</strong>的轻量入口：单次 run 只回答「这一次通没通过」，
 * 而回归/选型真正关心的是「同一任务在不同 Agent、不同引擎版本下分数怎么走」。本命令
 * 只读既有产物、纯离线聚合，不重跑评估，因此可反复执行、可放进 CI 归档。
 *
 * <p>聚合口径：
 * <ul>
 *   <li>扫描 {@code <runs-root>} 下所有 {@code <run>/report/report.json}（单次评估的原子产物，
 *       套件批跑的每次成员 run 同样落这一份），跳过 {@code <runs-root>/redteam/} 下的攻击 run；</li>
 *   <li>按 {@code (task_id, agent)} 分组给出趋势：运行次数、通过率、首/末/最佳分、判分确定性；</li>
 *   <li>若存在 {@code <runs-root>/redteam/redteam_report.json}，附带最近一次红队门禁摘要。</li>
 * </ul>
 *
 * <p>示例：
 * <pre>{@code
 * # 全量趋势
 * agent-eval history
 *
 * # 只看某任务、某几个 Agent，最近 20 条明细
 * agent-eval history --task api-payload-001 --agent gpt-x,claude-y --limit 20
 * }</pre>
 *
 * @author shiyongyin
 * @since 0.5.0
 */
@Command(name = "history", mixinStandardHelpOptions = true,
        description = "汇总 runs/ 历次评估产出跨 run / Agent / 版本的趋势报告（只读离线聚合）")
public final class HistoryCommand implements Callable<Integer> {

    @Option(names = "--runs-root", defaultValue = "runs",
            description = "runs 根目录（默认 ${DEFAULT-VALUE}）")
    private Path runsRoot;

    @Option(names = "--task", split = ",",
            description = "仅统计这些任务 id（逗号分隔）；缺省统计全部")
    private String[] onlyTasks;

    @Option(names = "--agent", split = ",",
            description = "仅统计这些 Agent 标签（逗号分隔）；缺省统计全部")
    private String[] onlyAgents;

    @Option(names = "--out",
            description = "报告输出目录（默认 <runs-root>/history/<时间戳>）")
    private Path out;

    @Option(names = "--limit", defaultValue = "50",
            description = "Markdown「最近运行」明细最多展示条数（默认 ${DEFAULT-VALUE}；JSON 始终全量）")
    private int limit;

    @Override
    public Integer call() {
        if (!Files.isDirectory(runsRoot)) {
            System.err.println("错误: runs 根目录不存在: " + runsRoot);
            return 1;
        }
        Set<String> taskFilter = toSet(onlyTasks);
        Set<String> agentFilter = toSet(onlyAgents);

        List<RunRecord> records;
        try {
            records = scanRuns(taskFilter, agentFilter);
        } catch (UncheckedIOException e) {
            System.err.println("错误: 扫描 runs 失败: " + e.getMessage());
            return 2;
        }
        if (records.isEmpty()) {
            System.err.println("错误: 在 " + runsRoot + " 未找到任何评估报告 report.json"
                    + (taskFilter.isEmpty() && agentFilter.isEmpty() ? "" : "（在当前过滤条件下）"));
            return 1;
        }
        records.sort(Comparator.comparing(RunRecord::startedAtSortKey).thenComparing(RunRecord::runId));

        List<Trend> trends = buildTrends(records);
        JsonNode redteam = readRedteam();

        ObjectNode json = buildJson(records, trends, redteam);
        String markdown = renderMarkdown(json, redteam);
        Path outDir = out != null ? out
                : runsRoot.resolve("history").resolve(Ids.newRunId().replace("run_", "history_"));
        Path reportJson = writeReports(outDir, json, markdown);

        printSummary(records, trends, redteam, reportJson);
        return 0;
    }

    // ---------------------------------------------------------------- 数据模型

    /**
     * 一次评估的历史快照（从单个 {@code report.json} 抽取的趋势相关字段）。
     *
     * @param runId run 标识
     * @param taskId 任务 id
     * @param agent Agent 标签
     * @param model 模型标识（可空串）
     * @param engineVersion 引擎版本
     * @param status run 终态
     * @param score 最佳分（无有效提交时为 {@code null}）
     * @param maxScore 满分
     * @param passed 是否通过
     * @param deterministic 判分是否确定性
     * @param canaryLeaks canary 泄露留痕计数
     * @param startedAt 起始时刻（原始字符串）
     * @param durationMs 墙钟耗时
     * @param runDir run 目录（相对 runs-root）
     */
    private record RunRecord(String runId, String taskId, String agent, String model,
                             String engineVersion, String status, Double score, int maxScore,
                             boolean passed, boolean deterministic, long canaryLeaks,
                             String startedAt, long durationMs, String runDir) {

        /** 排序键：可解析为时刻则用时刻，否则退化为原始字符串比较。 */
        String startedAtSortKey() {
            return startedAt == null ? "" : startedAt;
        }
    }

    /**
     * 一个 {@code (task_id, agent)} 分组的趋势聚合。
     *
     * @param taskId 任务 id
     * @param agent Agent 标签
     * @param count 运行次数
     * @param passCount 通过次数
     * @param firstScore 最早一次的分数（可空）
     * @param lastScore 最近一次的分数（可空）
     * @param bestScore 历史最佳分（可空）
     * @param maxScore 满分（取最近一次）
     * @param latestStatus 最近一次终态
     * @param allDeterministic 是否历次均为确定性判分
     * @param firstAt 最早时刻
     * @param lastAt 最近时刻
     */
    private record Trend(String taskId, String agent, int count, int passCount,
                         Double firstScore, Double lastScore, Double bestScore, int maxScore,
                         String latestStatus, boolean allDeterministic,
                         String firstAt, String lastAt) {

        double passRate() {
            return count == 0 ? 0 : Math.round((double) passCount / count * 10000) / 10000.0;
        }
    }

    // ---------------------------------------------------------------- 扫描

    private List<RunRecord> scanRuns(Set<String> taskFilter, Set<String> agentFilter) {
        Path redteamDir = runsRoot.resolve("redteam").toAbsolutePath().normalize();
        List<RunRecord> records = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(runsRoot)) {
            List<Path> reportFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> "report.json".equals(p.getFileName().toString()))
                    .filter(p -> p.getParent() != null
                            && "report".equals(p.getParent().getFileName().toString()))
                    .filter(p -> !p.toAbsolutePath().normalize().startsWith(redteamDir))
                    .toList();
            for (Path reportFile : reportFiles) {
                RunRecord record = parseReport(reportFile);
                if (record == null) {
                    continue;
                }
                if (!taskFilter.isEmpty() && !taskFilter.contains(record.taskId())) {
                    continue;
                }
                if (!agentFilter.isEmpty() && !agentFilter.contains(record.agent())) {
                    continue;
                }
                records.add(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(runsRoot.toString(), e);
        }
        return records;
    }

    /**
     * 解析单个 {@code report.json}；文件损坏或非法 JSON 时返回 {@code null}（跳过，不污染趋势）。
     */
    private RunRecord parseReport(Path reportFile) {
        JsonNode root;
        try {
            root = Jsons.json().readTree(Files.readString(reportFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        JsonNode run = root.path("run");
        if (run.isMissingNode()) {
            return null;
        }
        JsonNode best = root.path("best_attempt");
        Double score = best.isObject() && best.path("score").isNumber()
                ? best.path("score").asDouble() : null;
        int maxScore = best.isObject() ? best.path("max_score").asInt(0) : 0;
        boolean passed = best.isObject() && best.path("passed").asBoolean(false);
        boolean deterministic = root.path("reproducibility").path("best_attempt_judge")
                .path("deterministic").asBoolean(true);
        long canaryLeaks = root.path("safety").path("canary_leaks").asLong(0);

        Path runDir = reportFile.getParent().getParent();
        String relDir = runsRoot.relativize(runDir).toString();
        return new RunRecord(
                run.path("run_id").asText(runDir.getFileName().toString()),
                run.path("task_id").asText(""),
                run.path("agent").asText(""),
                run.path("model").asText(""),
                run.path("engine_version").asText(""),
                run.path("status").asText(""),
                score, maxScore, passed, deterministic, canaryLeaks,
                run.path("started_at").asText(""),
                run.path("duration_ms").asLong(0),
                relDir);
    }

    private JsonNode readRedteam() {
        Path redteamReport = runsRoot.resolve("redteam").resolve("redteam_report.json");
        if (!Files.isRegularFile(redteamReport)) {
            return null;
        }
        try {
            return Jsons.json().readTree(Files.readString(redteamReport, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 趋势聚合

    private List<Trend> buildTrends(List<RunRecord> sortedRecords) {
        Map<String, List<RunRecord>> byKey = new LinkedHashMap<>();
        for (RunRecord r : sortedRecords) {
            byKey.computeIfAbsent(r.taskId() + "\u0000" + r.agent(), k -> new ArrayList<>()).add(r);
        }
        List<Trend> trends = new ArrayList<>();
        for (List<RunRecord> group : byKey.values()) {
            RunRecord first = group.get(0);
            RunRecord last = group.get(group.size() - 1);
            int passCount = (int) group.stream().filter(RunRecord::passed).count();
            Double bestScore = group.stream().map(RunRecord::score)
                    .filter(java.util.Objects::nonNull).max(Double::compareTo).orElse(null);
            boolean allDeterministic = group.stream().allMatch(RunRecord::deterministic);
            trends.add(new Trend(first.taskId(), first.agent(), group.size(), passCount,
                    first.score(), last.score(), bestScore, last.maxScore(),
                    last.status(), allDeterministic, first.startedAt(), last.startedAt()));
        }
        trends.sort(Comparator.comparing(Trend::taskId).thenComparing(Trend::agent));
        return trends;
    }

    // ---------------------------------------------------------------- JSON

    private ObjectNode buildJson(List<RunRecord> records, List<Trend> trends, JsonNode redteam) {
        ObjectNode root = Jsons.json().createObjectNode();
        root.put("schema_version", 1);
        root.put("generated_at", Instant.now().toString());
        root.put("runs_root", runsRoot.toString());
        root.put("run_count", records.size());
        root.put("task_count", (int) records.stream().map(RunRecord::taskId).distinct().count());
        root.put("agent_count", (int) records.stream().map(RunRecord::agent).distinct().count());

        ArrayNode trendArr = root.putArray("trends");
        for (Trend t : trends) {
            ObjectNode node = trendArr.addObject();
            node.put("task_id", t.taskId());
            node.put("agent", t.agent());
            node.put("count", t.count());
            node.put("pass_count", t.passCount());
            node.put("pass_rate", t.passRate());
            putScore(node, "first_score", t.firstScore());
            putScore(node, "last_score", t.lastScore());
            putScore(node, "best_score", t.bestScore());
            node.put("max_score", t.maxScore());
            node.put("latest_status", t.latestStatus());
            node.put("deterministic", t.allDeterministic());
            node.put("first_at", t.firstAt());
            node.put("last_at", t.lastAt());
        }

        ArrayNode runArr = root.putArray("runs");
        for (RunRecord r : records) {
            ObjectNode node = runArr.addObject();
            node.put("run_id", r.runId());
            node.put("task_id", r.taskId());
            node.put("agent", r.agent());
            node.put("model", r.model());
            node.put("engine_version", r.engineVersion());
            node.put("status", r.status());
            putScore(node, "score", r.score());
            node.put("max_score", r.maxScore());
            node.put("passed", r.passed());
            node.put("deterministic", r.deterministic());
            node.put("canary_leaks", r.canaryLeaks());
            node.put("started_at", r.startedAt());
            node.put("duration_ms", r.durationMs());
            node.put("run_dir", r.runDir());
        }

        if (redteam != null) {
            ObjectNode rt = root.putObject("redteam");
            rt.put("generated_at", redteam.path("generated_at").asText(""));
            rt.put("gate", redteam.path("gate").asText(""));
            rt.put("allowed_vulnerable_baseline",
                    redteam.path("allowed_vulnerable_baseline").asInt(0));
            rt.set("counts", redteam.path("counts").isObject()
                    ? redteam.path("counts").deepCopy() : Jsons.json().createObjectNode());
        } else {
            root.putNull("redteam");
        }
        return root;
    }

    private static void putScore(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.doubleValue());
        }
    }

    // ---------------------------------------------------------------- Markdown

    private String renderMarkdown(ObjectNode json, JsonNode redteam) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 评估历史趋势\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append(row("runs 根目录", "`" + json.path("runs_root").asText() + "`"));
        sb.append(row("生成时刻", json.path("generated_at").asText()));
        sb.append(row("运行总数", String.valueOf(json.path("run_count").asInt())));
        sb.append(row("任务数 / Agent 数",
                json.path("task_count").asInt() + " / " + json.path("agent_count").asInt()));
        sb.append("\n");

        sb.append("## 趋势（任务 × Agent）\n\n");
        sb.append("| 任务 | Agent | 次数 | 通过 | 通过率 | 首次分 | 最新分 | 最佳分 | 最新状态 | 判分 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (JsonNode t : json.path("trends")) {
            sb.append("| `").append(t.path("task_id").asText())
                    .append("` | `").append(t.path("agent").asText())
                    .append("` | ").append(t.path("count").asInt())
                    .append(" | ").append(t.path("pass_count").asInt())
                    .append(" | ").append(pct(t.path("pass_rate").asDouble()))
                    .append(" | ").append(scoreCell(t.path("first_score"), t.path("max_score")))
                    .append(" | ").append(scoreCell(t.path("last_score"), t.path("max_score")))
                    .append(" | ").append(scoreCell(t.path("best_score"), t.path("max_score")))
                    .append(" | ").append(t.path("latest_status").asText())
                    .append(" | ").append(t.path("deterministic").asBoolean() ? "确定性" : "含非确定性")
                    .append(" |\n");
        }
        sb.append("\n");

        ArrayNode runs = (ArrayNode) json.path("runs");
        int shown = Math.min(limit, runs.size());
        sb.append("## 最近运行（倒序，最多 ").append(limit).append(" 条）\n\n");
        sb.append("| run_id | 任务 | Agent | 模型 | 引擎版本 | 状态 | 分数 | 通过 | canary | 起始 | 耗时(ms) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < shown; i++) {
            JsonNode r = runs.get(runs.size() - 1 - i);
            sb.append("| `").append(r.path("run_id").asText())
                    .append("` | `").append(r.path("task_id").asText())
                    .append("` | `").append(r.path("agent").asText())
                    .append("` | ").append(blankDash(r.path("model").asText()))
                    .append(" | ").append(blankDash(r.path("engine_version").asText()))
                    .append(" | ").append(r.path("status").asText())
                    .append(" | ").append(scoreCell(r.path("score"), r.path("max_score")))
                    .append(" | ").append(r.path("passed").asBoolean() ? "是" : "否")
                    .append(" | ").append(r.path("canary_leaks").asLong())
                    .append(" | ").append(r.path("started_at").asText())
                    .append(" | ").append(r.path("duration_ms").asLong())
                    .append(" |\n");
        }
        sb.append("\n");

        if (redteam != null) {
            JsonNode counts = redteam.path("counts");
            sb.append("## 红队门禁（最近一次）\n\n");
            sb.append("| 项 | 值 |\n|---|---|\n");
            sb.append(row("门禁", redteam.path("gate").asText()));
            sb.append(row("生成时刻", redteam.path("generated_at").asText()));
            sb.append(row("DEFENDED / VULNERABLE",
                    counts.path("defended").asInt(0) + " / " + counts.path("vulnerable").asInt(0)));
            sb.append(row("INFRA / CHECK",
                    counts.path("infra").asInt(0) + " / " + counts.path("check").asInt(0)));
            sb.append(row("允许 VULNERABLE 基线",
                    String.valueOf(redteam.path("allowed_vulnerable_baseline").asInt(0))));
            sb.append("\n");
        }
        return sb.toString();
    }

    private Path writeReports(Path outDir, ObjectNode json, String markdown) {
        try {
            Files.createDirectories(outDir);
            Path jsonFile = outDir.resolve("history.json");
            Path mdFile = outDir.resolve("history.md");
            Files.writeString(jsonFile, json.toPrettyString(), StandardCharsets.UTF_8);
            Files.writeString(mdFile, markdown, StandardCharsets.UTF_8);
            return jsonFile;
        } catch (IOException e) {
            throw new UncheckedIOException("写入历史报告失败: " + outDir, e);
        }
    }

    // ---------------------------------------------------------------- 摘要

    private void printSummary(List<RunRecord> records, List<Trend> trends,
                              JsonNode redteam, Path reportJson) {
        long passes = records.stream().filter(RunRecord::passed).count();
        System.out.println();
        System.out.println("========== 评估历史趋势 ==========");
        System.out.printf("运行 %d 次，任务 %d 个，Agent %d 个%n",
                records.size(),
                records.stream().map(RunRecord::taskId).distinct().count(),
                records.stream().map(RunRecord::agent).distinct().count());
        System.out.printf("整体通过率 %.1f%%（%d/%d）%n",
                records.isEmpty() ? 0.0 : (double) passes / records.size() * 100,
                passes, records.size());
        System.out.printf("%-24s %-16s %-6s %-8s %-8s %s%n",
                "TASK_ID", "AGENT", "次数", "通过率", "最新分", "最新状态");
        for (Trend t : trends) {
            System.out.printf("%-24s %-16s %-6d %-8s %-8s %s%n",
                    t.taskId(), t.agent(), t.count(), pct(t.passRate()),
                    t.lastScore() == null ? "—" : t.lastScore() + "/" + t.maxScore(),
                    t.latestStatus());
        }
        if (redteam != null) {
            System.out.println("红队门禁（最近一次）: " + redteam.path("gate").asText()
                    + "，DEFENDED=" + redteam.path("counts").path("defended").asInt(0)
                    + "，VULNERABLE=" + redteam.path("counts").path("vulnerable").asInt(0));
        }
        System.out.println("报告   : " + reportJson);
    }

    // ---------------------------------------------------------------- helper

    private static Set<String> toSet(String[] values) {
        return values == null ? Set.of() : new java.util.LinkedHashSet<>(Arrays.asList(values));
    }

    private static String row(String key, String value) {
        return "| " + key + " | " + value + " |\n";
    }

    private static String scoreCell(JsonNode score, JsonNode maxScore) {
        return score == null || score.isNull() ? "—"
                : score.asDouble() + "/" + maxScore.asInt();
    }

    private static String blankDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String pct(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", Math.round(rate * 1000) / 10.0);
    }
}
