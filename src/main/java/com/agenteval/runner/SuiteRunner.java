package com.agenteval.runner;

import com.agenteval.agent.AgentAdapter;
import com.agenteval.agent.CliAgentAdapter;
import com.agenteval.agent.DockerAgentAdapter;
import com.agenteval.agent.DockerSandbox;
import com.agenteval.agent.HttpAgentAdapter;
import com.agenteval.agent.ScriptedAgentAdapter;
import com.agenteval.state.RunStatus;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务集批跑器：用<strong>指定的 Agent</strong>对任务库中的每个任务执行一次或多次评估，
 * 汇总为「套件报告」；并支持多个 Agent 并列跑同一任务集，产出选型对比面板。
 *
 * <p>两类用途：
 * <ul>
 *   <li><strong>CI 冒烟回归</strong>：用脚本回放（{@link AgentSpec#scripted()}）把每个任务的
 *       「失败→修正→通过」闭环再走一遍，验证框架本身未劣化（确定性、可放进 CI）；</li>
 *   <li><strong>生产 Agent 度量 / 选型</strong>：用 {@code cli} 适配器驱动真实 Agent 批量过全部任务，
 *       可配 {@code repeat=k} 做 pass^k 可靠性度量（k 次全过才算「稳定通过」，借鉴 tau-bench），
 *       并可多 Agent 并列对比。</li>
 * </ul>
 *
 * <p>本类只负责编排与汇总，不决定进程退出码；退出码策略交由 CLI 根据
 * {@link SuiteResult#allPassed()} 决定，与 {@code run --fail-on-not-passed} 口径一致。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class SuiteRunner {

    private static final Logger log = LoggerFactory.getLogger(SuiteRunner.class);

    private SuiteRunner() {
    }

    // ---------------------------------------------------------------- 数据模型

    /**
     * Agent 规格：一个可读标签 + 「针对某任务目录构造适配器」的工厂。
     *
     * <p>{@code scripted} 需要每个任务各自的 {@code samples/replay.yaml}，故工厂按任务目录取文件；
     * {@code cli} 用同一条命令模板驱动全部任务，工厂忽略任务目录。
     *
     * @param label 标签（进入报告，用于多 Agent 对比区分列）
     * @param requiresReplay 是否要求任务具备 {@code samples/replay.yaml}（scripted 为 true）
     * @param factory 针对任务目录构造适配器的工厂
     */
    public record AgentSpec(String label, boolean requiresReplay, Function<Path, AgentAdapter> factory) {

        /**
         * 脚本回放 Agent（默认；用于 CI 冒烟回归）。
         *
         * @return 规格，标签为 {@code scripted}
         */
        public static AgentSpec scripted() {
            return new AgentSpec("scripted", true,
                    taskDir -> new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));
        }

        /**
         * CLI Agent（用真实命令行 Agent 驱动全部任务）。
         *
         * @param label 标签（用于对比面板区分）
         * @param cmd 命令模板（支持 {@code {instructions}} 等占位符，见 CliAgentAdapter）
         * @return 规格
         */
        public static AgentSpec cli(String label, String cmd) {
            return new AgentSpec(label == null || label.isBlank() ? "cli" : label, false,
                    taskDir -> new CliAgentAdapter(cmd));
        }

        /**
         * Docker 沙箱 CLI Agent（容器强隔离批跑；契约见 {@link DockerAgentAdapter}）。
         *
         * @param label 标签（用于对比面板区分）
         * @param cmd 命令模板（占位符替换为容器内路径）
         * @param sandbox docker 沙箱配置
         * @return 规格
         */
        public static AgentSpec dockerCli(String label, String cmd, DockerSandbox sandbox) {
            return new AgentSpec(label == null || label.isBlank() ? "docker" : label, false,
                    taskDir -> new DockerAgentAdapter(cmd, sandbox));
        }

        /**
         * HTTP Agent（评估服务形态的 Agent；契约见 {@link HttpAgentAdapter}）。
         *
         * @param label 标签（用于对比面板区分）
         * @param endpoint Agent 服务端点 URL
         * @param headers 附加请求头（形如 {@code "Authorization: Bearer xxx"}；可为 {@code null}）
         * @return 规格
         */
        public static AgentSpec http(String label, String endpoint, List<String> headers) {
            return new AgentSpec(label == null || label.isBlank() ? "http" : label, false,
                    taskDir -> new HttpAgentAdapter(endpoint, headers));
        }
    }

    /**
     * 单次 run 的结论（一个任务在一轮 repeat 中的一次执行）。
     *
     * @param index 第几次重复（从 1 开始）
     * @param status run 终态
     * @param passed 是否通过（{@code status == PASSED}）
     * @param score 最佳分数（无有效提交时为 {@code null}）
     * @param durationMs 本次 run 墙钟耗时（毫秒）
     * @param costUsd Agent 自报成本（美元；未上报为 {@code null}，自报口径不参与评分）
     * @param totalTokens Agent 自报 token 总量（未上报为 {@code null}）
     * @param runId run id
     * @param runDir run 目录
     * @param failedRuleIds 本次 run 最佳轮次仍失败的公开 rule_id 清单（不含 hidden 细节）
     */
    public record RunAttempt(int index, RunStatus status, boolean passed, Double score,
                             long durationMs, Double costUsd, Long totalTokens,
                             String runId, Path runDir, List<String> failedRuleIds) {
        public RunAttempt {
            failedRuleIds = failedRuleIds == null ? List.of() : List.copyOf(failedRuleIds);
        }
    }

    /**
     * 单个任务的批跑结论（聚合该任务的 k 次重复）。
     *
     * @param taskId 任务 id
     * @param taskDir 任务目录
     * @param passScore 通过线
     * @param maxScore 满分
     * @param runs 该任务的每次重复结果（起跑失败时为空）
     * @param error 起跑/框架异常信息（正常时为 {@code null}）
     */
    public record TaskResult(String taskId, Path taskDir, int passScore, int maxScore,
                             List<RunAttempt> runs, String error) {

        /**
         * 重复次数（实际完成的 run 次数）。
         *
         * @return 次数
         */
        public int repeats() {
            return runs.size();
        }

        /**
         * 通过的重复次数。
         *
         * @return 通过次数
         */
        public long passCount() {
            return runs.stream().filter(RunAttempt::passed).count();
        }

        /**
         * pass^k：k 次重复全部通过才算「稳定通过」（tau-bench 可靠性口径）。
         *
         * @return 全部通过时为 {@code true}
         */
        public boolean passAtK() {
            return !runs.isEmpty() && runs.stream().allMatch(RunAttempt::passed);
        }

        /**
         * pass@1：首次重复是否通过。
         *
         * @return 首跑通过时为 {@code true}
         */
        public boolean passAt1() {
            return !runs.isEmpty() && runs.get(0).passed();
        }

        /**
         * 代表性终态（首次重复的终态；起跑失败为 {@code null}）——供单 Agent、repeat=1 的场景取用。
         *
         * @return 终态或 {@code null}
         */
        public RunStatus status() {
            return runs.isEmpty() ? null : runs.get(0).status();
        }

        /**
         * 是否「稳定通过」，等价于 {@link #passAtK()}。
         *
         * @return 稳定通过为 {@code true}
         */
        public boolean passed() {
            return passAtK();
        }

        /**
         * 最佳分数（各次重复中的最高分；无有效分数为 {@code null}）。
         *
         * @return 最佳分数或 {@code null}
         */
        public Double bestScore() {
            return runs.stream().map(RunAttempt::score).filter(java.util.Objects::nonNull)
                    .max(Double::compareTo).orElse(null);
        }

        /**
         * 平均墙钟耗时（毫秒）。
         *
         * @return 平均耗时；无 run 时为 0
         */
        public long avgDurationMs() {
            return runs.isEmpty() ? 0
                    : Math.round(runs.stream().mapToLong(RunAttempt::durationMs).average().orElse(0));
        }

        /**
         * 各次重复的自报成本合计（美元）。
         *
         * @return 合计成本；无任何上报时为 {@code null}
         */
        public Double totalCostUsd() {
            List<Double> reported = runs.stream().map(RunAttempt::costUsd)
                    .filter(java.util.Objects::nonNull).toList();
            return reported.isEmpty() ? null
                    : Math.round(reported.stream().mapToDouble(Double::doubleValue).sum() * 1_000_000)
                            / 1_000_000.0;
        }

        /**
         * 各次重复的自报 token 合计。
         *
         * @return 合计 token；无任何上报时为 {@code null}
         */
        public Long totalTokens() {
            List<Long> reported = runs.stream().map(RunAttempt::totalTokens)
                    .filter(java.util.Objects::nonNull).toList();
            return reported.isEmpty() ? null
                    : reported.stream().mapToLong(Long::longValue).sum();
        }

        /**
         * 聚合该任务各次重复中出现过的失败 rule_id（去重且保持出现顺序）。
         *
         * @return 失败规则清单
         */
        public List<String> failedRuleIds() {
            return runs.stream()
                    .flatMap(a -> a.failedRuleIds().stream())
                    .distinct()
                    .toList();
        }
    }

    /**
     * 一个 Agent 跑完整个任务集的结论。
     *
     * @param agentLabel Agent 标签
     * @param tasksRoot 任务库根目录
     * @param repeat 每任务重复次数
     * @param startedAt 起始时刻
     * @param finishedAt 结束时刻
     * @param results 逐任务结论
     */
    public record SuiteResult(String agentLabel, Path tasksRoot, int repeat,
                              Instant startedAt, Instant finishedAt, List<TaskResult> results) {

        /**
         * 任务总数。
         *
         * @return 任务数
         */
        public int total() {
            return results.size();
        }

        /**
         * 稳定通过（pass^k）的任务数。
         *
         * @return 稳定通过数
         */
        public long passedCount() {
            return results.stream().filter(TaskResult::passAtK).count();
        }

        /**
         * 未稳定通过（含框架故障）的任务数。
         *
         * @return 未通过数
         */
        public long notPassedCount() {
            return total() - passedCount();
        }

        /**
         * 框架故障或起跑异常的任务数。
         *
         * @return 故障任务数
         */
        public long erroredCount() {
            return results.stream()
                    .filter(r -> r.error() != null || r.runs().stream().anyMatch(
                            a -> a.status() == RunStatus.ERROR || a.status() == RunStatus.INTEGRITY_BROKEN))
                    .count();
        }

        /**
         * 总 run 次数（Σ 每任务重复数）。
         *
         * @return 总次数
         */
        public long totalRuns() {
            return results.stream().mapToInt(TaskResult::repeats).sum();
        }

        /**
         * 总通过次数（跨任务、跨重复）。
         *
         * @return 通过次数
         */
        public long totalPasses() {
            return results.stream().mapToLong(TaskResult::passCount).sum();
        }

        /**
         * 通过率（总通过次数 / 总 run 次数）。
         *
         * @return 通过率（0~1）；无 run 时为 0
         */
        public double passRate() {
            long runs = totalRuns();
            return runs == 0 ? 0 : round4((double) totalPasses() / runs);
        }

        /**
         * 全部任务墙钟耗时平均（毫秒）。
         *
         * @return 平均耗时
         */
        public long avgDurationMs() {
            long runs = totalRuns();
            if (runs == 0) {
                return 0;
            }
            long sum = results.stream().flatMap(r -> r.runs().stream())
                    .mapToLong(RunAttempt::durationMs).sum();
            return Math.round((double) sum / runs);
        }

        /**
         * 批跑总耗时（毫秒）。
         *
         * @return 耗时
         */
        public long durationMs() {
            return Duration.between(startedAt, finishedAt).toMillis();
        }

        /**
         * 全套件的自报成本合计（美元）。
         *
         * @return 合计成本；无任何上报时为 {@code null}
         */
        public Double totalCostUsd() {
            List<Double> reported = results.stream().map(TaskResult::totalCostUsd)
                    .filter(java.util.Objects::nonNull).toList();
            return reported.isEmpty() ? null
                    : Math.round(reported.stream().mapToDouble(Double::doubleValue).sum() * 1_000_000)
                            / 1_000_000.0;
        }

        /**
         * 全套件的自报 token 合计。
         *
         * @return 合计 token；无任何上报时为 {@code null}
         */
        public Long totalTokens() {
            List<Long> reported = results.stream().map(TaskResult::totalTokens)
                    .filter(java.util.Objects::nonNull).toList();
            return reported.isEmpty() ? null
                    : reported.stream().mapToLong(Long::longValue).sum();
        }

        /**
         * 是否全部任务稳定通过（pass^k）——CI 冒烟门禁判据。
         *
         * @return 全部稳定通过为 {@code true}
         */
        public boolean allPassed() {
            return !results.isEmpty() && results.stream().allMatch(TaskResult::passAtK);
        }
    }

    /**
     * 多 Agent 对比结论。
     *
     * @param tasksRoot 任务库根目录
     * @param repeat 每任务重复次数
     * @param taskIds 参与对比的任务 id（各 Agent 对齐的行）
     * @param perAgent 每个 Agent 的套件结论（对比的列）
     */
    public record ComparisonResult(Path tasksRoot, int repeat, List<String> taskIds,
                                   List<SuiteResult> perAgent) {
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 用指定 Agent 批跑任务集（默认脚本回放、repeat=1 的便捷重载）。
     *
     * @param tasksRoot 任务库根目录
     * @param runsRoot runs 根目录
     * @param modelName 模型标识（可为 {@code null}）
     * @param onlyTaskIds 仅批跑这些任务 id；{@code null}/空表示全部
     * @return 批跑结论
     */
    public static SuiteResult run(Path tasksRoot, Path runsRoot, String modelName, Set<String> onlyTaskIds) {
        return run(tasksRoot, runsRoot, modelName, onlyTaskIds, AgentSpec.scripted(), 1);
    }

    /**
     * 用指定 Agent 批跑任务集。
     *
     * @param tasksRoot 任务库根目录
     * @param runsRoot runs 根目录
     * @param modelName 模型标识（可为 {@code null}）
     * @param onlyTaskIds 仅批跑这些任务 id；{@code null}/空表示全部
     * @param agent Agent 规格
     * @param repeat 每任务重复次数（≥1；>1 用于 pass^k 可靠性度量）
     * @return 批跑结论
     */
    public static SuiteResult run(Path tasksRoot, Path runsRoot, String modelName,
                                  Set<String> onlyTaskIds, AgentSpec agent, int repeat) {
        int k = Math.max(1, repeat);
        Instant startedAt = Instant.now();
        List<Path> taskDirs = discoverTasks(tasksRoot, onlyTaskIds);
        List<TaskResult> results = new ArrayList<>();
        for (Path taskDir : taskDirs) {
            results.add(runTask(taskDir, runsRoot, modelName, agent, k));
        }
        return new SuiteResult(agent.label(), tasksRoot, k, startedAt, Instant.now(), List.copyOf(results));
    }

    /**
     * 多个 Agent 并列跑同一任务集，产出对比结论。
     *
     * @param tasksRoot 任务库根目录
     * @param runsRoot runs 根目录
     * @param modelName 模型标识（可为 {@code null}）
     * @param onlyTaskIds 仅批跑这些任务 id；{@code null}/空表示全部
     * @param agents Agent 规格列表（对比的列）
     * @param repeat 每任务重复次数
     * @return 对比结论
     */
    public static ComparisonResult runComparison(Path tasksRoot, Path runsRoot, String modelName,
                                                 Set<String> onlyTaskIds, List<AgentSpec> agents, int repeat) {
        int k = Math.max(1, repeat);
        List<Path> taskDirs = discoverTasks(tasksRoot, onlyTaskIds);
        List<String> taskIds = taskDirs.stream().map(d -> d.getFileName().toString()).toList();
        List<SuiteResult> perAgent = new ArrayList<>();
        for (AgentSpec agent : agents) {
            Instant startedAt = Instant.now();
            List<TaskResult> results = new ArrayList<>();
            for (Path taskDir : taskDirs) {
                results.add(runTask(taskDir, runsRoot, modelName, agent, k));
            }
            perAgent.add(new SuiteResult(agent.label(), tasksRoot, k, startedAt, Instant.now(),
                    List.copyOf(results)));
        }
        return new ComparisonResult(tasksRoot, k, List.copyOf(taskIds), List.copyOf(perAgent));
    }

    private static TaskResult runTask(Path taskDir, Path runsRoot, String modelName,
                                      AgentSpec agent, int repeat) {
        String taskId = taskDir.getFileName().toString();
        try {
            TaskSpec spec = TaskSpecLoader.load(taskDir);
            taskId = spec.taskId();
            if (agent.requiresReplay() && !Files.isRegularFile(taskDir.resolve("samples/replay.yaml"))) {
                return new TaskResult(taskId, taskDir, spec.scoring().passScore(),
                        spec.scoring().maxScore(), List.of(), "缺少 samples/replay.yaml，scripted 跳过");
            }
            List<RunAttempt> runs = new ArrayList<>();
            for (int i = 1; i <= repeat; i++) {
                long start = System.nanoTime();
                RunManager.RunOutcome outcome = RunManager.run(
                        taskDir, runsRoot, modelName, agent.factory().apply(taskDir));
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                JsonNode runReport = readRunReport(outcome.runDir());
                JsonNode cost = readRunCost(runReport);
                runs.add(new RunAttempt(i, outcome.status(), outcome.status() == RunStatus.PASSED,
                        outcome.bestScore(), durationMs,
                        cost == null ? null : cost.path("cost_usd").asDouble(0),
                        cost == null ? null : cost.path("total_tokens").asLong(0),
                        outcome.runId(), outcome.runDir(), readFailedRuleIds(runReport)));
            }
            TaskResult result = new TaskResult(taskId, taskDir, spec.scoring().passScore(),
                    spec.scoring().maxScore(), List.copyOf(runs), null);
            log.info("[suite:{}] {} → pass {}/{}（pass^{}={}）", agent.label(), taskId,
                    result.passCount(), result.repeats(), repeat, result.passAtK());
            return result;
        } catch (RuntimeException e) {
            log.error("[suite:{}] {} 批跑异常: {}", agent.label(), taskId, e.getMessage());
            return new TaskResult(taskId, taskDir, 0, 0, List.of(), String.valueOf(e.getMessage()));
        }
    }

    /**
     * 读取单次 run 报告（缺失或不可读时为 {@code null}）。
     *
     * @param runDir run 目录
     * @return {@code report.json} 根节点
     */
    private static JsonNode readRunReport(Path runDir) {
        Path reportFile = runDir.resolve("report/report.json");
        if (!Files.isRegularFile(reportFile)) {
            return null;
        }
        try {
            return Jsons.json().readTree(Files.readString(reportFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取 run 报告失败（忽略）: {}", reportFile);
            return null;
        }
    }

    private static JsonNode readRunCost(JsonNode report) {
        if (report == null) {
            return null;
        }
        JsonNode cost = report.path("cost");
        return cost.path("reported").asBoolean(false) ? cost : null;
    }

    private static List<String> readFailedRuleIds(JsonNode report) {
        if (report == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        JsonNode bestAttempt = report.path("best_attempt");
        if (!bestAttempt.isMissingNode() && !bestAttempt.isNull()) {
            JsonNode bestFailed = bestAttempt.path("failed_rules");
            bestFailed.forEach(rule -> {
                String id = rule.path("rule_id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            });
            return ids.stream().distinct().toList();
        } else {
            report.path("attempts").forEach(attempt ->
                    attempt.path("failed_rule_ids").forEach(id -> {
                        String value = id.asText("");
                        if (!value.isBlank()) {
                            ids.add(value);
                        }
                    }));
            return ids.stream().distinct().toList();
        }
    }

    /**
     * 发现任务库中的任务（含 {@code task.yaml}）。
     *
     * <p>不再强制要求 {@code samples/replay.yaml}——是否需要回放脚本取决于 Agent 类型，
     * 由 {@link #runTask} 按 {@link AgentSpec#requiresReplay()} 决定；这样 cli 真实 Agent
     * 可覆盖没有回放脚本的任务。
     *
     * @param tasksRoot 任务库根目录
     * @param onlyTaskIds 过滤集合（按目录名匹配）；{@code null}/空表示不过滤
     * @return 排序后的任务目录列表
     */
    private static List<Path> discoverTasks(Path tasksRoot, Set<String> onlyTaskIds) {
        if (!Files.isDirectory(tasksRoot)) {
            throw new IllegalArgumentException("任务库目录不存在: " + tasksRoot);
        }
        boolean filtered = onlyTaskIds != null && !onlyTaskIds.isEmpty();
        try (Stream<Path> stream = Files.list(tasksRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("task.yaml")))
                    .filter(dir -> !filtered || onlyTaskIds.contains(dir.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描任务库失败: " + tasksRoot, e);
        }
    }

    // ---------------------------------------------------------------- 报告落盘

    /**
     * 将单 Agent 批跑结论落盘为 {@code suite_report.json} 与 {@code suite_report.md}。
     *
     * @param result 批跑结论
     * @param outDir 输出目录（自动创建）
     * @return {@code suite_report.json} 路径
     */
    public static Path writeReports(SuiteResult result, Path outDir) {
        ObjectNode json = buildSuiteJson(result);
        String markdown = renderSuiteMarkdown(json);
        return writeBoth(outDir, json, markdown);
    }

    /**
     * 将多 Agent 对比结论落盘为 {@code suite_report.json} 与 {@code suite_report.md}。
     *
     * @param result 对比结论
     * @param outDir 输出目录（自动创建）
     * @return {@code suite_report.json} 路径
     */
    public static Path writeComparisonReports(ComparisonResult result, Path outDir) {
        ObjectNode json = buildComparisonJson(result);
        String markdown = renderComparisonMarkdown(result);
        return writeBoth(outDir, json, markdown);
    }

    private static Path writeBoth(Path outDir, ObjectNode json, String markdown) {
        try {
            Files.createDirectories(outDir);
            Path jsonFile = outDir.resolve("suite_report.json");
            Path mdFile = outDir.resolve("suite_report.md");
            Files.writeString(jsonFile, json.toPrettyString(), StandardCharsets.UTF_8);
            Files.writeString(mdFile, markdown, StandardCharsets.UTF_8);
            return jsonFile;
        } catch (IOException e) {
            throw new UncheckedIOException("写入套件报告失败: " + outDir, e);
        }
    }

    // ---------------------------------------------------------------- json（单 Agent）

    private static ObjectNode buildSuiteJson(SuiteResult result) {
        ObjectNode root = Jsons.json().createObjectNode();
        root.put("schema_version", 1);
        root.put("mode", "single");

        ObjectNode suite = root.putObject("suite");
        suite.put("agent", result.agentLabel());
        suite.put("tasks_root", result.tasksRoot().toString());
        suite.put("repeat", result.repeat());
        suite.put("started_at", result.startedAt().toString());
        suite.put("finished_at", result.finishedAt().toString());
        suite.put("duration_ms", result.durationMs());
        suite.put("total", result.total());
        suite.put("passed", result.passedCount());
        suite.put("not_passed", result.notPassedCount());
        suite.put("errored", result.erroredCount());
        suite.put("total_runs", result.totalRuns());
        suite.put("total_passes", result.totalPasses());
        suite.put("pass_rate", result.passRate());
        suite.put("avg_duration_ms", result.avgDurationMs());
        putNullable(suite, "total_cost_usd", result.totalCostUsd());
        putNullableLong(suite, "total_tokens", result.totalTokens());
        suite.put("all_passed", result.allPassed());

        ArrayNode results = root.putArray("results");
        for (TaskResult r : result.results()) {
            results.add(taskJson(r, result.repeat()));
        }
        root.set("risk_summary", buildRiskSummary(result));
        return root;
    }

    private static ObjectNode buildRiskSummary(SuiteResult result) {
        ObjectNode summary = Jsons.json().createObjectNode();
        ArrayNode notPassed = summary.putArray("not_passed_tasks");
        ArrayNode setupErrors = summary.putArray("setup_error_tasks");
        ArrayNode flaky = summary.putArray("flaky_tasks");
        Map<String, Integer> byRule = new TreeMap<>();
        for (TaskResult r : result.results()) {
            if (!r.passAtK()) {
                notPassed.add(r.taskId());
            }
            if (r.error() != null || r.runs().stream().anyMatch(a ->
                    a.status() == RunStatus.ERROR || a.status() == RunStatus.INTEGRITY_BROKEN)) {
                setupErrors.add(r.taskId());
            }
            if (result.repeat() > 1 && r.passAt1() && !r.passAtK()) {
                flaky.add(r.taskId());
            }
            r.runs().forEach(a -> a.failedRuleIds().forEach(rule -> byRule.merge(rule, 1, Integer::sum)));
        }
        summary.set("failed_rules_by_id", Jsons.json().valueToTree(new LinkedHashMap<>(byRule)));
        summary.put("action_required", notPassed.size() > 0 || setupErrors.size() > 0);
        return summary;
    }

    private static ObjectNode taskJson(TaskResult r, int repeat) {
        ObjectNode node = Jsons.json().createObjectNode();
        node.put("task_id", r.taskId());
        node.put("status", r.status() == null ? "SETUP_ERROR" : r.status().name());
        node.put("passed", r.passAtK());
        node.put("pass_at_k", r.passAtK());
        node.put("pass_at_1", r.passAt1());
        node.put("pass_count", r.passCount());
        node.put("repeat", repeat);
        if (r.bestScore() == null) {
            node.putNull("score");
        } else {
            node.put("score", r.bestScore());
        }
        node.put("pass_score", r.passScore());
        node.put("max_score", r.maxScore());
        node.put("avg_duration_ms", r.avgDurationMs());
        putNullable(node, "total_cost_usd", r.totalCostUsd());
        putNullableLong(node, "total_tokens", r.totalTokens());
        node.set("failed_rule_ids", Jsons.json().valueToTree(r.failedRuleIds()));
        if (r.error() == null) {
            node.putNull("error");
        } else {
            node.put("error", r.error());
        }
        ArrayNode runs = node.putArray("runs");
        for (RunAttempt a : r.runs()) {
            ObjectNode rn = runs.addObject();
            rn.put("index", a.index());
            rn.put("status", a.status() == null ? "SETUP_ERROR" : a.status().name());
            rn.put("passed", a.passed());
            if (a.score() == null) {
                rn.putNull("score");
            } else {
                rn.put("score", a.score());
            }
            rn.put("duration_ms", a.durationMs());
            putNullable(rn, "cost_usd", a.costUsd());
            putNullableLong(rn, "total_tokens", a.totalTokens());
            rn.put("run_id", a.runId() == null ? "" : a.runId());
            rn.put("run_dir", a.runDir() == null ? "" : a.runDir().toString());
            rn.set("failed_rule_ids", Jsons.json().valueToTree(a.failedRuleIds()));
        }
        return node;
    }

    private static void putNullable(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.doubleValue());
        }
    }

    private static void putNullableLong(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.longValue());
        }
    }

    // ---------------------------------------------------------------- json（对比）

    private static ObjectNode buildComparisonJson(ComparisonResult result) {
        ObjectNode root = Jsons.json().createObjectNode();
        root.put("schema_version", 1);
        root.put("mode", "comparison");
        root.put("tasks_root", result.tasksRoot().toString());
        root.put("repeat", result.repeat());

        ArrayNode agents = root.putArray("agents");
        for (SuiteResult s : result.perAgent()) {
            ObjectNode a = agents.addObject();
            a.put("agent", s.agentLabel());
            a.put("total", s.total());
            a.put("passed", s.passedCount());
            a.put("pass_rate", s.passRate());
            a.put("avg_duration_ms", s.avgDurationMs());
            putNullable(a, "total_cost_usd", s.totalCostUsd());
            putNullableLong(a, "total_tokens", s.totalTokens());
            a.put("all_passed", s.allPassed());
            ArrayNode results = a.putArray("results");
            for (TaskResult r : s.results()) {
                results.add(taskJson(r, s.repeat()));
            }
        }
        return root;
    }

    // ---------------------------------------------------------------- markdown（单 Agent）

    private static String renderSuiteMarkdown(ObjectNode report) {
        var suite = report.path("suite");
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务集评估汇总\n\n");

        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append(row("Agent", "`" + suite.path("agent").asText() + "`"));
        sb.append(row("任务库", "`" + suite.path("tasks_root").asText() + "`"));
        sb.append(row("总体结论", suite.path("all_passed").asBoolean()
                ? "**全部稳定通过**" : "**存在未通过项**"));
        sb.append(row("任务数", String.valueOf(suite.path("total").asInt())));
        sb.append(row("稳定通过 / 未通过",
                suite.path("passed").asLong() + " / " + suite.path("not_passed").asLong()));
        int repeat = suite.path("repeat").asInt();
        if (repeat > 1) {
            sb.append(row("重复次数 (pass^k)", "k = " + repeat));
            sb.append(row("通过率", pct(suite.path("pass_rate").asDouble())
                    + "（" + suite.path("total_passes").asLong() + "/" + suite.path("total_runs").asLong() + " 次）"));
        }
        sb.append(row("框架故障", String.valueOf(suite.path("errored").asLong())));
        sb.append(row("平均单次耗时", suite.path("avg_duration_ms").asLong() + " ms"));
        sb.append(row("总耗时", suite.path("duration_ms").asLong() + " ms"));
        if (!suite.path("total_cost_usd").isNull()) {
            sb.append(row("总成本（自报）", "$" + suite.path("total_cost_usd").asDouble()
                    + "（" + suite.path("total_tokens").asLong() + " tokens）"));
        }
        sb.append("\n");

        appendSuiteActionSummary(sb, report);

        sb.append("## 逐任务明细\n\n");
        if (repeat > 1) {
            sb.append("| 任务 | pass^k | pass@1 | 通过次数 | 最佳分 | 通过线 | 平均耗时(ms) |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            for (var r : report.path("results")) {
                sb.append("| `").append(r.path("task_id").asText())
                        .append("` | ").append(r.path("pass_at_k").asBoolean() ? "是" : "否")
                        .append(" | ").append(r.path("pass_at_1").asBoolean() ? "是" : "否")
                        .append(" | ").append(r.path("pass_count").asLong()).append("/").append(repeat)
                        .append(" | ").append(scoreCell(r))
                        .append(" | ").append(r.path("pass_score").asInt())
                        .append(" | ").append(r.path("avg_duration_ms").asLong()).append(" |\n");
            }
        } else {
            sb.append("| 任务 | 状态 | 得分 | 通过线 | 通过 | 耗时(ms) |\n|---|---|---|---|---|---|\n");
            for (var r : report.path("results")) {
                sb.append("| `").append(r.path("task_id").asText())
                        .append("` | ").append(r.path("status").asText())
                        .append(" | ").append(scoreCell(r))
                        .append(" | ").append(r.path("pass_score").asInt())
                        .append(" | ").append(r.path("passed").asBoolean() ? "是" : "否")
                        .append(" | ").append(r.path("avg_duration_ms").asLong()).append(" |\n");
            }
        }
        sb.append("\n");
        appendErrors(sb, report.path("results"));
        return sb.toString();
    }

    private static void appendSuiteActionSummary(StringBuilder sb, ObjectNode report) {
        JsonNode risk = report.path("risk_summary");
        if (risk.isMissingNode()) {
            return;
        }
        sb.append("## 小团队操作摘要\n\n");
        if (!risk.path("action_required").asBoolean(false)) {
            sb.append("- 当前没有未稳定通过任务或框架故障；可把本次 suite 作为当前基线。\n");
        } else {
            sb.append("- 未稳定通过任务：").append(formatTaskList(risk.path("not_passed_tasks"))).append("\n");
            sb.append("- 框架故障 / 完整性异常任务：").append(formatTaskList(risk.path("setup_error_tasks"))).append("\n");
            sb.append("- flaky 任务（pass@1 过但 pass^k 未过）：")
                    .append(formatTaskList(risk.path("flaky_tasks"))).append("\n");
        }
        JsonNode rules = risk.path("failed_rules_by_id");
        if (rules.isObject() && !rules.isEmpty()) {
            sb.append("- 失败规则热点：");
            boolean first = true;
            var fields = rules.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!first) {
                    sb.append("，");
                }
                first = false;
                sb.append("`").append(entry.getKey()).append("` × ").append(entry.getValue().asInt());
            }
            sb.append("\n");
        } else {
            sb.append("- 失败规则热点：无\n");
        }
        sb.append("\n");
    }

    // ---------------------------------------------------------------- markdown（对比）

    private static String renderComparisonMarkdown(ComparisonResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 多 Agent 对比面板\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append(row("任务库", "`" + result.tasksRoot() + "`"));
        sb.append(row("参与 Agent", String.valueOf(result.perAgent().size())));
        sb.append(row("任务数", String.valueOf(result.taskIds().size())));
        sb.append(row("重复次数 (pass^k)", "k = " + result.repeat()));
        sb.append("\n");

        // Agent 汇总排名（按稳定通过数、再按通过率降序）。
        List<SuiteResult> ranked = new ArrayList<>(result.perAgent());
        ranked.sort((a, b) -> {
            int byPass = Long.compare(b.passedCount(), a.passedCount());
            return byPass != 0 ? byPass : Double.compare(b.passRate(), a.passRate());
        });
        sb.append("## Agent 汇总（按稳定通过数排序）\n\n");
        sb.append("| 排名 | Agent | 稳定通过(pass^k) | 通过率 | 平均耗时(ms) | 总成本(自报) | 全过 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int rank = 1;
        for (SuiteResult s : ranked) {
            sb.append("| ").append(rank++)
                    .append(" | `").append(s.agentLabel()).append("`")
                    .append(" | ").append(s.passedCount()).append("/").append(s.total())
                    .append(" | ").append(pct(s.passRate()))
                    .append(" | ").append(s.avgDurationMs())
                    .append(" | ").append(s.totalCostUsd() == null ? "—" : "$" + s.totalCostUsd())
                    .append(" | ").append(s.allPassed() ? "是" : "否").append(" |\n");
        }
        sb.append("\n");

        appendComparisonHotspots(sb, result);

        // 任务 × Agent 明细矩阵（单元格为 pass^k 与最佳分）。
        sb.append("## 任务 × Agent 矩阵（pass^k · 最佳分）\n\n");
        sb.append("| 任务 |");
        for (SuiteResult s : result.perAgent()) {
            sb.append(" `").append(s.agentLabel()).append("` |");
        }
        sb.append("\n|---|");
        for (int i = 0; i < result.perAgent().size(); i++) {
            sb.append("---|");
        }
        sb.append("\n");
        for (String taskId : result.taskIds()) {
            sb.append("| `").append(taskId).append("` |");
            for (SuiteResult s : result.perAgent()) {
                TaskResult r = s.results().stream()
                        .filter(t -> t.taskId().equals(taskId)).findFirst().orElse(null);
                sb.append(" ").append(cell(r)).append(" |");
            }
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static void appendComparisonHotspots(StringBuilder sb, ComparisonResult result) {
        List<String> failedByAll = new ArrayList<>();
        List<String> splitTasks = new ArrayList<>();
        for (String taskId : result.taskIds()) {
            long pass = result.perAgent().stream()
                    .map(s -> s.results().stream()
                            .filter(t -> t.taskId().equals(taskId)).findFirst().orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .filter(TaskResult::passAtK)
                    .count();
            if (pass == 0) {
                failedByAll.add(taskId);
            } else if (pass < result.perAgent().size()) {
                splitTasks.add(taskId);
            }
        }
        sb.append("## 失败热点\n\n");
        sb.append("- 全部 Agent 都未稳定通过：").append(formatIds(failedByAll))
                .append("（优先检查任务说明、judge 规则或环境）\n");
        sb.append("- Agent 之间有区分度：").append(formatIds(splitTasks))
                .append("（优先用于选型和回归定位）\n\n");
    }

    private static String cell(TaskResult r) {
        if (r == null) {
            return "—";
        }
        if (r.error() != null) {
            return "ERR";
        }
        String mark = r.passAtK() ? "✅" : "❌";
        String score = r.bestScore() == null ? "—" : String.valueOf(r.bestScore());
        return mark + " " + score + "（" + r.passCount() + "/" + r.repeats() + "）";
    }

    // ---------------------------------------------------------------- helper

    private static void appendErrors(StringBuilder sb, com.fasterxml.jackson.databind.JsonNode results) {
        boolean anyError = false;
        for (var r : results) {
            if (!r.path("error").isNull()) {
                anyError = true;
                break;
            }
        }
        if (anyError) {
            sb.append("## 框架故障 / 跳过详情\n\n");
            for (var r : results) {
                if (!r.path("error").isNull()) {
                    sb.append("- `").append(r.path("task_id").asText())
                            .append("`: ").append(r.path("error").asText()).append("\n");
                }
            }
            sb.append("\n");
        }
    }

    private static String scoreCell(com.fasterxml.jackson.databind.JsonNode r) {
        return r.path("score").isNull() ? "—"
                : r.path("score").asDouble() + "/" + r.path("max_score").asInt();
    }

    private static String formatTaskList(com.fasterxml.jackson.databind.JsonNode tasks) {
        if (!tasks.isArray() || tasks.isEmpty()) {
            return "无";
        }
        List<String> ids = new ArrayList<>();
        tasks.forEach(task -> ids.add("`" + task.asText() + "`"));
        return String.join(", ", ids);
    }

    private static String formatIds(List<String> ids) {
        if (ids.isEmpty()) {
            return "无";
        }
        return ids.stream().map(id -> "`" + id + "`").collect(java.util.stream.Collectors.joining(", "));
    }

    private static String row(String key, String value) {
        return "| " + key + " | " + value + " |\n";
    }

    private static String pct(double rate) {
        return Math.round(rate * 1000) / 10.0 + "%";
    }

    private static double round4(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
