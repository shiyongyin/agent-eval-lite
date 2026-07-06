package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.runner.SuiteRunner;
import com.agenteval.state.RunStatus;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 任务集批跑器的端到端回归：验证 {@link SuiteRunner} 能把任务库中每个任务的回放闭环
 * 跑通并正确汇总，同时验证「过滤 / 报告落盘 / 全通过判据」这三条 CI 门禁依赖的能力。
 *
 * <p>在此基础上覆盖三条「生产度量」能力：真实 cli Agent（真实进程 + 环境变量 + inbox 提交，
 * 不用 mock）批量过任务、{@code repeat=k} 的 pass^k 可靠性口径（含抓出「首跑过、复跑挂」的
 * 不稳定 Agent）、多 Agent 并列对比与对比报告落盘。
 *
 * <p>需要 {@code javac} 的编译类任务（code-fix-001）在无编译器环境下会判失败——本测试对
 * 「全部通过」的强断言仅在 javac 可用时执行，其余断言与编译器无关，保证 CI 与本地都稳定。
 */
class SuiteRunnerTest {

    @TempDir
    Path runsRoot;

    @TempDir
    Path outDir;

    @TempDir
    Path stateDir;

    @Test
    void 全量批跑_每个任务回放闭环均达通过_并正确汇总() throws Exception {
        SuiteRunner.SuiteResult result = SuiteRunner.run(
                Path.of("tasks"), runsRoot, "suite-test", Set.of());

        // 任务库中五个任务都具备可回放脚本，应全部被发现并批跑。
        assertThat(result.total()).isEqualTo(5);
        // 框架自身不应出现故障（judge_failure / 起跑异常）。
        assertThat(result.erroredCount()).isZero();

        // 与编译器无关的四个任务的回放必定收敛到 PASSED。
        assertThat(statusOf(result, "api-payload-001")).isEqualTo(RunStatus.PASSED);
        assertThat(statusOf(result, "tool-call-001")).isEqualTo(RunStatus.PASSED);
        assertThat(statusOf(result, "doc-analysis-001")).isEqualTo(RunStatus.PASSED);
        assertThat(statusOf(result, "prd-review-001")).isEqualTo(RunStatus.PASSED);

        if (javacAvailable()) {
            assertThat(statusOf(result, "code-fix-001")).isEqualTo(RunStatus.PASSED);
            assertThat(result.allPassed()).isTrue();
            assertThat(result.passedCount()).isEqualTo(5);
        }
    }

    @Test
    void 报告落盘_json结构完整且与内存结论一致() throws Exception {
        SuiteRunner.SuiteResult result = SuiteRunner.run(
                Path.of("tasks"), runsRoot, null, Set.of());
        Path reportJson = SuiteRunner.writeReports(result, outDir);

        assertThat(reportJson).isRegularFile();
        assertThat(outDir.resolve("suite_report.md")).isRegularFile();

        JsonNode report = Jsons.json().readTree(Files.readString(reportJson));
        assertThat(report.path("suite").path("total").asInt()).isEqualTo(result.total());
        assertThat(report.path("suite").path("passed").asLong()).isEqualTo(result.passedCount());
        assertThat(report.path("suite").path("all_passed").asBoolean()).isEqualTo(result.allPassed());
        assertThat(report.path("results")).hasSize(result.total());

        // Markdown 汇总应包含任务集标题与逐任务明细表头。
        String md = Files.readString(outDir.resolve("suite_report.md"));
        assertThat(md).contains("任务集评估汇总").contains("逐任务明细");
    }

    @Test
    void 过滤批跑_仅跑指定任务() {
        SuiteRunner.SuiteResult result = SuiteRunner.run(
                Path.of("tasks"), runsRoot, null, Set.of("tool-call-001"));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.results().get(0).taskId()).isEqualTo("tool-call-001");
        assertThat(result.results().get(0).status()).isEqualTo(RunStatus.PASSED);
        assertThat(result.allPassed()).isTrue();
    }

    @Test
    void 真实cli代理_repeat2_两次全过_passK成立且时延被采集() throws Exception {
        SuiteRunner.AgentSpec agent = SuiteRunner.AgentSpec.cli("shell-copy", goodAgentCmd());

        SuiteRunner.SuiteResult result = SuiteRunner.run(
                Path.of("tasks"), runsRoot, null, Set.of("api-payload-001"), agent, 2);

        assertThat(result.agentLabel()).isEqualTo("shell-copy");
        assertThat(result.repeat()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(1);

        SuiteRunner.TaskResult task = result.results().get(0);
        assertThat(task.repeats()).isEqualTo(2);
        assertThat(task.passCount()).isEqualTo(2);
        assertThat(task.passAtK()).isTrue();
        // 时延为真实墙钟耗时：每次 run 至少要起真实 shell 进程 + 判分，必大于 0。
        assertThat(task.runs()).allSatisfy(run -> {
            assertThat(run.passed()).isTrue();
            assertThat(run.durationMs()).isGreaterThan(0);
        });
        assertThat(result.allPassed()).isTrue();
        assertThat(result.passRate()).isEqualTo(1.0);

        // 报告落盘：单 Agent 模式应带 repeat / runs 明细与时延聚合。
        Path reportJson = SuiteRunner.writeReports(result, outDir);
        JsonNode report = Jsons.json().readTree(Files.readString(reportJson));
        assertThat(report.path("mode").asText()).isEqualTo("single");
        assertThat(report.path("suite").path("agent").asText()).isEqualTo("shell-copy");
        assertThat(report.path("suite").path("repeat").asInt()).isEqualTo(2);
        assertThat(report.path("suite").path("avg_duration_ms").asLong()).isGreaterThan(0);
        JsonNode taskNode = report.path("results").get(0);
        assertThat(taskNode.path("pass_at_k").asBoolean()).isTrue();
        assertThat(taskNode.path("runs")).hasSize(2);
    }

    @Test
    void passK可靠性_不稳定agent首跑过复跑挂_判为未稳定通过() {
        // 第一次被调起时正常提交，之后永远不再提交——模拟「偶尔能过」的不稳定 Agent。
        Path counter = stateDir.resolve("invocations");
        String cmd = "n=$(cat '" + counter + "' 2>/dev/null || echo 0); n=$((n+1)); "
                + "printf %s \"$n\" > '" + counter + "'; "
                + "if [ \"$n\" -eq 1 ]; then " + goodAgentCmd() + "; fi";
        SuiteRunner.AgentSpec agent = SuiteRunner.AgentSpec.cli("flaky", cmd);

        SuiteRunner.SuiteResult result = SuiteRunner.run(
                Path.of("tasks"), runsRoot, null, Set.of("api-payload-001"), agent, 2);

        SuiteRunner.TaskResult task = result.results().get(0);
        assertThat(task.passAt1()).isTrue();
        assertThat(task.passCount()).isEqualTo(1);
        // pass@1 会误判它「可用」；pass^2 抓出不稳定：两次未全过，不算稳定通过。
        assertThat(task.passAtK()).isFalse();
        assertThat(result.passedCount()).isZero();
        assertThat(result.allPassed()).isFalse();
        assertThat(result.totalRuns()).isEqualTo(2);
        assertThat(result.totalPasses()).isEqualTo(1);
        assertThat(result.passRate()).isEqualTo(0.5);
    }

    @Test
    void agent自报usage_套件聚合出成本列() throws Exception {
        // cli Agent 在提交里自报 usage：套件应逐 run 采集并聚合出任务/套件级成本。
        Path sample = Path.of("tasks/api-payload-001/samples/attempt-pass.json").toAbsolutePath();
        String cmd = "sed -e \"s/[{]attempt_id[}]/${AEL_ATTEMPT_ID}/\" "
                + "-e 's/\"schema_version\": 1,/\"schema_version\": 1, \"usage\": "
                + "{\"model\": \"m\", \"input_tokens\": 1000, \"output_tokens\": 500, \"cost_usd\": 0.02},/' "
                + "'" + sample + "' > \"${AEL_INBOX}/${AEL_ATTEMPT_ID}.json\"";
        SuiteRunner.AgentSpec agent = SuiteRunner.AgentSpec.cli("usage-agent", cmd);

        SuiteRunner.SuiteResult result = SuiteRunner.run(
                Path.of("tasks"), runsRoot, null, Set.of("api-payload-001"), agent, 2);

        SuiteRunner.TaskResult task = result.results().get(0);
        assertThat(task.passAtK()).isTrue();
        assertThat(task.runs()).allSatisfy(run -> {
            assertThat(run.costUsd()).isEqualTo(0.02);
            assertThat(run.totalTokens()).isEqualTo(1500L);
        });
        assertThat(task.totalCostUsd()).isEqualTo(0.04);
        assertThat(task.totalTokens()).isEqualTo(3000L);
        assertThat(result.totalCostUsd()).isEqualTo(0.04);

        // 报告落盘：JSON 带成本字段，Markdown 呈现自报成本行。
        Path reportJson = SuiteRunner.writeReports(result, outDir);
        JsonNode report = Jsons.json().readTree(Files.readString(reportJson));
        assertThat(report.path("suite").path("total_cost_usd").asDouble()).isEqualTo(0.04);
        assertThat(report.path("suite").path("total_tokens").asLong()).isEqualTo(3000);
        assertThat(report.path("results").get(0).path("runs").get(0).path("cost_usd").asDouble())
                .isEqualTo(0.02);
        assertThat(Files.readString(outDir.resolve("suite_report.md"))).contains("总成本（自报）");
    }

    @Test
    void 未上报usage_套件成本列为空不误标() {
        SuiteRunner.SuiteResult result = SuiteRunner.run(
                Path.of("tasks"), runsRoot, null, Set.of("api-payload-001"));
        assertThat(result.totalCostUsd()).isNull();
        assertThat(result.totalTokens()).isNull();
        assertThat(result.results().get(0).totalCostUsd()).isNull();
    }

    @Test
    void 多Agent对比_scripted与cli并列_矩阵报告落盘() throws Exception {
        List<SuiteRunner.AgentSpec> agents = List.of(
                SuiteRunner.AgentSpec.scripted(),
                SuiteRunner.AgentSpec.cli("shell-copy", goodAgentCmd()));

        SuiteRunner.ComparisonResult cmp = SuiteRunner.runComparison(
                Path.of("tasks"), runsRoot, null, Set.of("api-payload-001"), agents, 1);

        assertThat(cmp.taskIds()).containsExactly("api-payload-001");
        assertThat(cmp.perAgent()).hasSize(2);
        assertThat(cmp.perAgent().get(0).agentLabel()).isEqualTo("scripted");
        assertThat(cmp.perAgent().get(1).agentLabel()).isEqualTo("shell-copy");
        assertThat(cmp.perAgent()).allSatisfy(s -> assertThat(s.allPassed()).isTrue());

        Path reportJson = SuiteRunner.writeComparisonReports(cmp, outDir);
        JsonNode report = Jsons.json().readTree(Files.readString(reportJson));
        assertThat(report.path("mode").asText()).isEqualTo("comparison");
        assertThat(report.path("agents")).hasSize(2);

        String md = Files.readString(outDir.resolve("suite_report.md"));
        assertThat(md).contains("多 Agent 对比面板")
                .contains("任务 × Agent")
                .contains("`scripted`")
                .contains("`shell-copy`");
    }

    /**
     * 真实 cli Agent 命令：把任务自带的合格提交样例替换 attempt_id 后写入 inbox。
     *
     * <p>走的是 CliAgentAdapter 的完整真实链路（/bin/sh 进程、AEL_* 环境变量、inbox 落盘），
     * 仅提交内容取自样例以保持确定性；sed 模式用 {@code [{]...[}]} 写法避开适配器的模板占位符替换。
     *
     * @return shell 命令模板
     */
    private static String goodAgentCmd() {
        Path sample = Path.of("tasks/api-payload-001/samples/attempt-pass.json").toAbsolutePath();
        return "sed \"s/[{]attempt_id[}]/${AEL_ATTEMPT_ID}/\" '" + sample
                + "' > \"${AEL_INBOX}/${AEL_ATTEMPT_ID}.json\"";
    }

    private static RunStatus statusOf(SuiteRunner.SuiteResult result, String taskId) {
        return result.results().stream()
                .filter(r -> r.taskId().equals(taskId))
                .map(SuiteRunner.TaskResult::status)
                .findFirst()
                .orElseThrow(() -> new AssertionError("套件结果缺少任务: " + taskId));
    }

    private static boolean javacAvailable() {
        try {
            Process process = new ProcessBuilder("javac", "-version")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
