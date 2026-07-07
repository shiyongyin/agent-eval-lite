package com.agenteval.cli;

import com.agenteval.judge.RulesFile;
import com.agenteval.task.JudgeType;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecException;
import com.agenteval.task.TaskSpecLoader;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval validate}：任务规格静态体检（结构 + 引用 + 规则文件 + 规则深度 lint），
 * 供任务作者在提交任务前自查，也作为 CI 的任务库门禁。
 *
 * <p>深度 lint 把「run 时才会炸的规则配置错误」前移到静态阶段：
 * {@code expected_from} 的文件与 JSON 指针必须可解析、{@code schema_file} 必须存在、
 * {@code tool_call_required} / {@code world_state} 引用的工具必须在任务 {@code allowed_tools}
 * 白名单内（否则任务先天不可通关）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "validate", mixinStandardHelpOptions = true, description = "校验任务定义是否合法完整")
public final class ValidateCommand implements Callable<Integer> {

    @Option(names = "--task", required = true, description = "任务目录")
    private Path taskDir;

    @Override
    public Integer call() {
        TaskSpec spec;
        try {
            spec = TaskSpecLoader.load(taskDir);
        } catch (TaskSpecException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        int checkCount = 0;
        if (spec.judge().type() == JudgeType.RULES || spec.judge().type() == JudgeType.HYBRID) {
            RulesFile rules = RulesFile.load(
                    taskDir.resolve(spec.judge().rulesFile()),
                    spec.scoring().dimensions().stream()
                            .map(TaskSpec.Dimension::name)
                            .collect(Collectors.toSet()));
            checkCount = rules.checks().size();

            List<String> lintErrors = lintRules(spec, taskDir, rules);
            if (!lintErrors.isEmpty()) {
                System.err.println("规则深度 lint 未通过 [" + spec.taskId() + "]:");
                lintErrors.forEach(error -> System.err.println("  - " + error));
                return 1;
            }
        }
        System.out.println("OK  " + spec.taskId()
                + "（" + spec.taskType().jsonName()
                + "，tier=" + spec.tier().jsonName()
                + (spec.labels().isEmpty() ? "" : "，labels=" + String.join(",", spec.labels()))
                + "，judge=" + spec.judge().type().jsonName()
                + "，checks=" + checkCount
                + "，维度=" + spec.scoring().dimensions().size()
                + "，通过线=" + spec.scoring().passScore() + "/" + spec.scoring().maxScore() + "）");
        // 提示（不算失败）：声明了真实后端但还没有 replay 应答库的工具，默认模式下调用会失败。
        for (TaskSpec.AllowedTool tool : spec.allowedTools()) {
            if (tool.backend() != null
                    && !Files.isRegularFile(taskDir.resolve("hidden/tools/" + tool.name() + ".responses.yaml"))) {
                System.out.println("提示: 工具 " + tool.name()
                        + " 声明了 http 后端但无 replay 应答库——默认 replay 模式下调用将失败，"
                        + "请先 AEL_TOOL_MODE=live 录制并把 <run>/tools/" + tool.name()
                        + ".recorded.yaml 晋升为 hidden/tools/" + tool.name() + ".responses.yaml");
            }
        }
        return 0;
    }

    /**
     * 规则深度 lint：静态解析各 check 的隐藏引用与工具白名单一致性。
     *
     * @param spec 任务规格
     * @param taskDir 任务目录
     * @param rules 已通过结构校验的规则文件
     * @return 问题清单（空表示通过）
     */
    static List<String> lintRules(TaskSpec spec, Path taskDir, RulesFile rules) {
        Path hiddenDir = taskDir.resolve("hidden");
        Set<String> allowedTools = spec.allowedTools().stream()
                .map(TaskSpec.AllowedTool::name)
                .collect(Collectors.toSet());
        List<String> errors = new ArrayList<>();

        for (RulesFile.CheckDef check : rules.checks()) {
            String expectedFrom = check.raw().path("expected_from").asText("");
            if (!expectedFrom.isBlank()) {
                lintExpectedFrom(check.id(), expectedFrom, hiddenDir, errors);
            }
            String schemaFile = check.raw().path("schema_file").asText("");
            if (!schemaFile.isBlank() && !Files.isRegularFile(hiddenDir.resolve(schemaFile))) {
                errors.add("check " + check.id() + " 的 schema_file 不存在: hidden/" + schemaFile);
            }
            if ("tool_call_required".equals(check.type())) {
                String tool = check.raw().path("tool").asText("");
                if (!tool.isBlank() && !allowedTools.contains(tool)) {
                    errors.add("check " + check.id() + " 要求调用工具 " + tool
                            + "，但它不在 allowed_tools 白名单内（任务先天不可通关）");
                }
            }
            if ("world_state".equals(check.type())) {
                for (JsonNode toolNode : check.raw().path("tools")) {
                    String tool = toolNode.asText("");
                    if (!tool.isBlank() && !allowedTools.contains(tool)) {
                        errors.add("check " + check.id() + " 的终态工具 " + tool
                                + " 不在 allowed_tools 白名单内（任务先天不可通关）");
                    }
                }
            }
            if ("llm_rubric".equals(check.type())) {
                String rubricFile = check.raw().path("rubric_file").asText("");
                if (rubricFile.isBlank()) {
                    errors.add("check " + check.id() + " 缺少 rubric_file");
                } else if (!Files.isRegularFile(hiddenDir.resolve(rubricFile))) {
                    errors.add("check " + check.id() + " 的 rubric_file 不存在: hidden/" + rubricFile);
                }
                if (check.blocking()) {
                    errors.add("check " + check.id()
                            + " 是 llm_rubric，禁止设为 blocking（非确定性信号不能一票否决）");
                }
            }
        }
        lintLlmWeightShare(spec, rules, errors);
        return errors;
    }

    /**
     * llm_rubric 有效权重占比上限（30%）：LLM 是非确定性信号，只允许承担低权重的主观维度
     * （设计 §5.6）。有效权重 = Σ各维度 [维度权重 × 该维度内 llm 点数占比]。
     */
    private static void lintLlmWeightShare(TaskSpec spec, RulesFile rules, List<String> errors) {
        Map<String, double[]> pointsByDimension = new LinkedHashMap<>();
        for (RulesFile.CheckDef check : rules.checks()) {
            double[] acc = pointsByDimension.computeIfAbsent(check.dimension(), k -> new double[2]);
            acc[0] += check.points();
            if ("llm_rubric".equals(check.type())) {
                acc[1] += check.points();
            }
        }
        double llmEffectiveWeight = 0;
        for (Map.Entry<String, double[]> entry : pointsByDimension.entrySet()) {
            double total = entry.getValue()[0];
            double llm = entry.getValue()[1];
            if (total > 0 && llm > 0) {
                llmEffectiveWeight += spec.scoring().weightOf(entry.getKey()) * llm / total;
            }
        }
        double cap = spec.scoring().maxScore() * 0.3;
        if (llmEffectiveWeight > cap + 1e-9) {
            errors.add(String.format(Locale.ROOT,
                    "llm_rubric 有效权重 %.1f 超过上限 %.1f（max_score 的 30%%）——"
                            + "LLM 判分只允许承担低权重主观维度，请下调 points 或维度权重", llmEffectiveWeight, cap));
        }
    }

    private static void lintExpectedFrom(String checkId, String expectedFrom,
                                         Path hiddenDir, List<String> errors) {
        String[] parts = expectedFrom.split("#", 2);
        Path file = hiddenDir.resolve(parts[0]);
        if (!Files.isRegularFile(file)) {
            errors.add("check " + checkId + " 的 expected_from 文件不存在: hidden/" + parts[0]);
            return;
        }
        try {
            JsonNode root = Jsons.json().readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (parts.length > 1 && root.at(parts[1]).isMissingNode()) {
                errors.add("check " + checkId + " 的 expected_from 指针不可解析: " + expectedFrom);
            }
        } catch (IOException e) {
            errors.add("check " + checkId + " 的 expected_from 文件不是合法 JSON: hidden/" + parts[0]);
        }
    }
}
