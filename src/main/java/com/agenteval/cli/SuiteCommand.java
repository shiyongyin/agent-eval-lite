package com.agenteval.cli;

import com.agenteval.agent.DockerSandbox;
import com.agenteval.runner.SuiteRunner;
import com.agenteval.runner.SuiteRunner.AgentSpec;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import com.agenteval.task.TaskTier;
import com.agenteval.util.Ids;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval suite}：用指定 Agent 批跑任务库并产出套件汇总 / 多 Agent 对比报告。
 *
 * <p>三种典型用法：
 * <ul>
 *   <li><strong>CI 冒烟门禁</strong>（默认 scripted 回放）：一条命令把「隔离 / 契约 / 隐藏判分 /
 *       受控反馈 / 留痕 / 可复现报告」链路在全部任务上跑一遍，任一闭环回归被破坏都会被挡下；</li>
 *   <li><strong>真实 Agent 度量</strong>：{@code --agent cli --cmd '...'} 让真实命令行 Agent
 *       批量过全部任务，配 {@code --repeat k} 得到 pass^k 可靠性（k 次全过才算稳定通过）；</li>
 *   <li><strong>多 Agent 选型对比</strong>：{@code --agents-file agents.yaml} 让多个 Agent
 *       并列跑同一任务集，产出「任务 × Agent」矩阵与排名面板。</li>
 * </ul>
 *
 * <p>退出码约定（与 {@code run --fail-on-not-passed} 对齐）：0 = 正常完成；
 * 3 = 指定 {@code --fail-on-not-passed} 且存在未稳定通过任务（CI 门禁失败）；
 * 2 = 批跑器自身故障或参数错误。
 *
 * <p>示例：
 * <pre>{@code
 * # 全量冒烟并在有未通过项时以退出码 3 结束（CI 门禁）
 * agent-eval suite --tasks-root tasks --fail-on-not-passed
 *
 * # 真实 cli agent 批量过全部任务，每任务重复 3 次测 pass^3
 * agent-eval suite --agent cli --cmd 'bash my_agent.sh' --label my-agent --repeat 3
 *
 * # 多 Agent 并列对比
 * agent-eval suite --agents-file agents.yaml --repeat 2
 * }</pre>
 *
 * <p>{@code agents.yaml} 格式：
 * <pre>{@code
 * agents:
 *   - label: baseline-scripted
 *     type: scripted
 *   - label: my-agent
 *     type: cli
 *     cmd: 'bash my_agent.sh'
 *   - label: my-service
 *     type: http
 *     endpoint: http://localhost:8080/agent
 *     headers:                       # 可选
 *       - 'Authorization: Bearer xxx'
 * }</pre>
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "suite", mixinStandardHelpOptions = true,
        description = "批跑任务库并生成套件汇总 / 多 Agent 对比报告（CI 冒烟门禁、pass^k 可靠性）")
public final class SuiteCommand implements Callable<Integer> {

    @Option(names = "--tasks-root", defaultValue = "tasks",
            description = "任务库根目录（默认 ${DEFAULT-VALUE}）")
    private Path tasksRoot;

    @Option(names = "--runs-root", defaultValue = "runs",
            description = "runs 根目录（默认 ${DEFAULT-VALUE}）")
    private Path runsRoot;

    @Option(names = "--tasks", split = ",",
            description = "仅批跑这些任务 id（逗号分隔）；缺省批跑全部任务")
    private String[] onlyTasks;

    @Option(names = "--tier", split = ",",
            description = "仅批跑这些分层的任务（逗号分隔：smoke / regression / security / domain）；"
                    + "与 --tasks 取交集")
    private String[] tiers;

    @Option(names = "--agent", defaultValue = "scripted",
            description = "Agent 类型：scripted（回放，默认）、cli（命令行 Agent）或 http（服务型 Agent）")
    private String agentType;

    @Option(names = "--cmd",
            description = "cli Agent 的命令模板（--agent cli 时必填，支持 {instructions} 等占位符）")
    private String cmd;

    @Option(names = "--endpoint",
            description = "http Agent 的服务端点 URL（--agent http 时必填）")
    private String endpoint;

    @Option(names = "--sandbox", defaultValue = "none",
            description = "cli Agent 执行沙箱：none（宿主直跑，默认）| docker（容器强隔离；单 Agent 与 agents.yaml 的 cli 项统一生效）")
    private String sandbox;

    @Option(names = "--sandbox-image",
            description = "docker 沙箱镜像（--sandbox docker 时必填）")
    private String sandboxImage;

    @Option(names = "--sandbox-network", defaultValue = "none",
            description = "docker 沙箱网络模式（默认 none 断网；需联网的 Agent 用 bridge）")
    private String sandboxNetwork;

    @Option(names = "--sandbox-tool-jar",
            description = "挂入容器供 `agent-eval tool call` 使用的 CLI jar（默认自动探测当前运行 jar）")
    private Path sandboxToolJar;

    @Option(names = "--sandbox-docker-arg",
            description = "透传给 docker run 的附加参数（如 --memory=512m，可重复）")
    private List<String> sandboxDockerArgs;

    @Option(names = "--http-header",
            description = "http Agent 的附加请求头（形如 'Authorization: Bearer xxx'，可重复）")
    private List<String> httpHeaders;

    @Option(names = "--label", description = "Agent 标签（进入报告；默认取 Agent 类型名）")
    private String label;

    @Option(names = "--repeat", defaultValue = "1",
            description = "每任务重复次数 k（>1 时产出 pass^k 可靠性，默认 ${DEFAULT-VALUE}）")
    private int repeat;

    @Option(names = "--agents-file",
            description = "多 Agent 对比配置（YAML）；指定后进入对比模式，忽略 --agent/--cmd/--label")
    private Path agentsFile;

    @Option(names = "--model", description = "模型标识（仅用于报告归档）")
    private String model;

    @Option(names = "--out", description = "套件报告输出目录（默认 <runs-root>/suite/<时间戳>）")
    private Path out;

    @Option(names = "--fail-on-not-passed",
            description = "存在未稳定通过任务时以退出码 3 结束（CI 门禁用；对比模式下忽略）")
    private boolean failOnNotPassed;

    @Override
    public Integer call() {
        Set<String> filter = onlyTasks == null ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(onlyTasks));
        if (repeat < 1) {
            System.err.println("错误: --repeat 必须 ≥ 1");
            return 2;
        }
        if (tiers != null && tiers.length > 0) {
            try {
                filter = resolveTierFilter(filter);
            } catch (IllegalArgumentException e) {
                System.err.println("错误: " + e.getMessage());
                return 2;
            }
            if (filter.isEmpty()) {
                System.err.println("错误: 没有任务匹配分层过滤条件 --tier "
                        + String.join(",", tiers)
                        + (onlyTasks == null ? "" : "（且受 --tasks 限定）"));
                return 1;
            }
        }
        Path outDir = out != null ? out
                : runsRoot.resolve("suite").resolve(Ids.newRunId().replace("run_", "suite_"));

        DockerSandbox sandboxConfig;
        try {
            SandboxSupport.validateSandboxValue(sandbox);
            sandboxConfig = SandboxSupport.isDocker(sandbox)
                    ? SandboxSupport.build(sandboxImage, sandboxNetwork, sandboxToolJar, sandboxDockerArgs)
                    : null;
        } catch (IllegalArgumentException e) {
            System.err.println("错误: " + e.getMessage());
            return 2;
        }

        return agentsFile != null
                ? runComparison(filter, outDir, sandboxConfig)
                : runSingle(filter, outDir, sandboxConfig);
    }

    /**
     * 把 {@code --tier} 解析为任务 id 集合，并与既有 {@code --tasks} 过滤取交集。
     *
     * <p>做法：扫描任务库、逐个加载 task.yaml，保留分层命中的任务 id。无法解析的任务目录
     * 无法归类分层，此处静默跳过（其结构问题另由 {@code validate} / 批跑本身暴露）。
     *
     * @param taskIdFilter 既有的 {@code --tasks} 过滤集合（空表示不限定）
     * @return 分层命中且落在 {@code --tasks} 限定内的任务 id 集合（保持任务库排序）
     * @throws IllegalArgumentException {@code --tier} 取值非法时
     */
    private Set<String> resolveTierFilter(Set<String> taskIdFilter) {
        Set<TaskTier> wanted = new LinkedHashSet<>();
        for (String raw : tiers) {
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                wanted.add(TaskTier.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知的分层: " + value
                        + "（可选 smoke / regression / security / domain）");
            }
        }
        if (!Files.isDirectory(tasksRoot)) {
            throw new IllegalArgumentException("任务库目录不存在: " + tasksRoot);
        }
        Set<String> matched = new LinkedHashSet<>();
        try (java.util.stream.Stream<Path> stream = Files.list(tasksRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("task.yaml")))
                    .sorted()
                    .forEach(dir -> {
                        TaskSpec spec;
                        try {
                            spec = TaskSpecLoader.load(dir);
                        } catch (RuntimeException e) {
                            return;
                        }
                        if (wanted.contains(spec.tier())
                                && (taskIdFilter.isEmpty() || taskIdFilter.contains(spec.taskId()))) {
                            matched.add(spec.taskId());
                        }
                    });
        } catch (IOException e) {
            throw new IllegalArgumentException("扫描任务库失败: " + tasksRoot + "（" + e.getMessage() + "）");
        }
        return matched;
    }

    // ---------------------------------------------------------------- 单 Agent 模式

    private Integer runSingle(Set<String> filter, Path outDir, DockerSandbox sandboxConfig) {
        AgentSpec agent;
        try {
            agent = buildAgent(sandboxConfig);
        } catch (IllegalArgumentException e) {
            System.err.println("错误: " + e.getMessage());
            return 2;
        }

        SuiteRunner.SuiteResult result;
        try {
            result = SuiteRunner.run(tasksRoot, runsRoot, model, filter, agent, repeat);
        } catch (RuntimeException e) {
            System.err.println("错误: 批跑失败: " + e.getMessage());
            return 2;
        }
        if (result.total() == 0) {
            System.err.println("错误: 未在 " + tasksRoot + " 找到任何任务"
                    + (filter.isEmpty() ? "" : "（过滤条件: " + String.join(",", filter) + "）"));
            return 1;
        }

        Path reportJson = SuiteRunner.writeReports(result, outDir);
        printSuiteSummary(result, reportJson);

        if (failOnNotPassed && !result.allPassed()) {
            return 3;
        }
        return 0;
    }

    private AgentSpec buildAgent(DockerSandbox sandboxConfig) {
        // 与 run --agent 的解析口径一致：大小写不敏感（RunCommand 用 toLowerCase 归一）。
        return switch (agentType.toLowerCase(Locale.ROOT)) {
            case "scripted" -> {
                if (cmd != null) {
                    throw new IllegalArgumentException("--cmd 仅在 --agent cli 时有效");
                }
                if (sandboxConfig != null) {
                    throw new IllegalArgumentException("--sandbox docker 仅适用于 --agent cli");
                }
                yield label == null ? AgentSpec.scripted()
                        : new AgentSpec(label, true, AgentSpec.scripted().factory());
            }
            case "cli" -> {
                if (cmd == null || cmd.isBlank()) {
                    throw new IllegalArgumentException("--agent cli 需要 --cmd 提供命令模板");
                }
                yield sandboxConfig != null
                        ? AgentSpec.dockerCli(label, cmd, sandboxConfig)
                        : AgentSpec.cli(label, cmd);
            }
            case "http" -> {
                if (endpoint == null || endpoint.isBlank()) {
                    throw new IllegalArgumentException("--agent http 需要 --endpoint 提供服务 URL");
                }
                if (sandboxConfig != null) {
                    throw new IllegalArgumentException("--sandbox docker 仅适用于 --agent cli");
                }
                yield AgentSpec.http(label, endpoint, httpHeaders);
            }
            default -> throw new IllegalArgumentException(
                    "不支持的 Agent 类型: " + agentType + "（可选 scripted / cli / http）");
        };
    }

    // ---------------------------------------------------------------- 对比模式

    private Integer runComparison(Set<String> filter, Path outDir, DockerSandbox sandboxConfig) {
        List<AgentSpec> agents;
        try {
            agents = parseAgentsFile(agentsFile, sandboxConfig);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("错误: 解析 --agents-file 失败: " + e.getMessage());
            return 2;
        }
        if (failOnNotPassed) {
            System.err.println("提示: 对比模式用于选型度量，--fail-on-not-passed 被忽略");
        }

        SuiteRunner.ComparisonResult result;
        try {
            result = SuiteRunner.runComparison(tasksRoot, runsRoot, model, filter, agents, repeat);
        } catch (RuntimeException e) {
            System.err.println("错误: 批跑失败: " + e.getMessage());
            return 2;
        }
        if (result.taskIds().isEmpty()) {
            System.err.println("错误: 未在 " + tasksRoot + " 找到任何任务");
            return 1;
        }

        Path reportJson = SuiteRunner.writeComparisonReports(result, outDir);
        printComparisonSummary(result, reportJson);
        return 0;
    }

    /**
     * 解析多 Agent 对比配置（宿主直跑口径，cli 项不套容器）。
     *
     * @param file agents.yaml 路径
     * @return Agent 规格列表
     * @throws IOException 文件读取失败
     * @throws IllegalArgumentException 配置不合法
     */
    static List<AgentSpec> parseAgentsFile(Path file) throws IOException {
        return parseAgentsFile(file, null);
    }

    /**
     * 解析多 Agent 对比配置。
     *
     * @param file agents.yaml 路径
     * @param sandboxConfig docker 沙箱配置（非 {@code null} 时对所有 cli 项统一套容器；scripted/http 项不受影响）
     * @return Agent 规格列表
     * @throws IOException 文件读取失败
     * @throws IllegalArgumentException 配置不合法
     */
    static List<AgentSpec> parseAgentsFile(Path file, DockerSandbox sandboxConfig) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        JsonNode root = Jsons.yaml().readTree(Files.readString(file));
        JsonNode agents = root.path("agents");
        if (!agents.isArray() || agents.isEmpty()) {
            throw new IllegalArgumentException("agents 列表为空（需要 agents: [...]）");
        }
        List<AgentSpec> specs = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode node : agents) {
            index++;
            String type = node.path("type").asText("");
            String agentLabel = node.path("label").asText("");
            AgentSpec spec = switch (type.toLowerCase(Locale.ROOT)) {
                case "scripted" -> agentLabel.isBlank() ? AgentSpec.scripted()
                        : new AgentSpec(agentLabel, true, AgentSpec.scripted().factory());
                case "cli" -> {
                    String agentCmd = node.path("cmd").asText("");
                    if (agentCmd.isBlank()) {
                        throw new IllegalArgumentException("第 " + index + " 个 agent（cli）缺少 cmd");
                    }
                    String cliLabel = agentLabel.isBlank() ? "cli-" + index : agentLabel;
                    yield sandboxConfig != null
                            ? AgentSpec.dockerCli(cliLabel, agentCmd, sandboxConfig)
                            : AgentSpec.cli(cliLabel, agentCmd);
                }
                case "http" -> {
                    String agentEndpoint = node.path("endpoint").asText("");
                    if (agentEndpoint.isBlank()) {
                        throw new IllegalArgumentException("第 " + index + " 个 agent（http）缺少 endpoint");
                    }
                    List<String> headers = new ArrayList<>();
                    node.path("headers").forEach(h -> headers.add(h.asText()));
                    yield AgentSpec.http(agentLabel.isBlank() ? "http-" + index : agentLabel,
                            agentEndpoint, headers);
                }
                default -> throw new IllegalArgumentException(
                        "第 " + index + " 个 agent 类型不合法: " + type + "（可选 scripted / cli / http）");
            };
            if (!labels.add(spec.label())) {
                throw new IllegalArgumentException("agent 标签重复: " + spec.label());
            }
            specs.add(spec);
        }
        return specs;
    }

    // ---------------------------------------------------------------- 摘要输出

    private static void printSuiteSummary(SuiteRunner.SuiteResult result, Path reportJson) {
        System.out.println();
        System.out.println("========== 任务集评估汇总 ==========");
        System.out.printf("Agent: %s，repeat: k=%d%n", result.agentLabel(), result.repeat());
        System.out.printf("%-24s %-16s %-10s %-8s %-10s %s%n",
                "TASK_ID", "STATUS", "SCORE", "PASS", "耗时(ms)", "");
        for (SuiteRunner.TaskResult r : result.results()) {
            String status = r.status() == null ? "SETUP_ERROR" : r.status().name();
            String score = r.bestScore() == null ? "—" : r.bestScore() + "/" + r.maxScore();
            String pass = result.repeat() > 1
                    ? r.passCount() + "/" + r.repeats() + (r.passAtK() ? " ✓" : " ✗")
                    : (r.passed() ? "✓" : "✗");
            System.out.printf("%-24s %-16s %-10s %-8s %-10d %s%n",
                    r.taskId(), status, score, pass, r.avgDurationMs(),
                    r.error() == null ? "" : "(" + r.error() + ")");
        }
        System.out.println("-----------------------------------");
        System.out.printf("总计 %d：稳定通过 %d，未通过 %d（其中故障 %d）%n",
                result.total(), result.passedCount(), result.notPassedCount(), result.erroredCount());
        if (result.repeat() > 1) {
            System.out.printf("pass^%d 口径：通过率 %.1f%%（%d/%d 次），平均单次耗时 %d ms%n",
                    result.repeat(), result.passRate() * 100,
                    result.totalPasses(), result.totalRuns(), result.avgDurationMs());
        }
        System.out.printf("总耗时 %d ms%n", result.durationMs());
        if (result.totalCostUsd() != null) {
            System.out.printf("总成本（Agent 自报）: $%s（%d tokens）%n",
                    result.totalCostUsd(), result.totalTokens());
        }
        System.out.println("总体结论: " + (result.allPassed() ? "全部稳定通过" : "存在未通过项"));
        System.out.println("报告   : " + reportJson);
    }

    private static void printComparisonSummary(SuiteRunner.ComparisonResult result, Path reportJson) {
        System.out.println();
        System.out.println("========== 多 Agent 对比面板 ==========");
        System.out.printf("任务数 %d，重复 k=%d%n", result.taskIds().size(), result.repeat());
        System.out.printf("%-20s %-18s %-10s %-12s %-12s %s%n",
                "AGENT", "稳定通过(pass^k)", "通过率", "平均耗时(ms)", "成本(自报)", "全过");
        for (SuiteRunner.SuiteResult s : result.perAgent()) {
            System.out.printf("%-20s %-18s %-10s %-12d %-12s %s%n",
                    s.agentLabel(),
                    s.passedCount() + "/" + s.total(),
                    String.format("%.1f%%", s.passRate() * 100),
                    s.avgDurationMs(),
                    s.totalCostUsd() == null ? "—" : "$" + s.totalCostUsd(),
                    s.allPassed() ? "是" : "否");
        }
        System.out.println("报告   : " + reportJson);
    }
}
