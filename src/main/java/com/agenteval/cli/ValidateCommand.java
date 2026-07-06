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
import java.util.List;
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
                + "，judge=" + spec.judge().type().jsonName()
                + "，checks=" + checkCount
                + "，维度=" + spec.scoring().dimensions().size()
                + "，通过线=" + spec.scoring().passScore() + "/" + spec.scoring().maxScore() + "）");
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
        }
        return errors;
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
