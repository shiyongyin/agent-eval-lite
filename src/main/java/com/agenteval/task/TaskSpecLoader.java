package com.agenteval.task;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * task.yaml 加载器：解析 + 默认值归一 + 全量校验，任一错误即拒绝起跑（fail-fast）。
 *
 * <p>校验分两层：<strong>结构层</strong>（必填字段、枚举合法、权重和=满分、通过线区间）与
 * <strong>引用层</strong>（visible_context / rules_file / script / 工具 mock 应答库等引用的
 * 文件必须真实存在）。错误全部收集后经 {@link TaskSpecException} 一次性抛出，
 * 避免「改一个错再冒一个错」的反复试错。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class TaskSpecLoader {

    private TaskSpecLoader() {
    }

    /**
     * 从任务目录加载并校验任务规格。
     *
     * @param taskDir 任务目录（须包含 task.yaml）
     * @return 合法的任务规格
     * @throws TaskSpecException 规格非法或引用文件缺失时
     */
    public static TaskSpec load(Path taskDir) {
        Path yamlFile = taskDir.resolve("task.yaml");
        if (!Files.isRegularFile(yamlFile)) {
            throw new TaskSpecException(taskDir.toString(), List.of("缺少 task.yaml"));
        }
        JsonNode root;
        try {
            root = Jsons.yaml().readTree(Files.readString(yamlFile));
        } catch (IOException e) {
            throw new TaskSpecException(taskDir.toString(), List.of("task.yaml 解析失败: " + e.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        TaskSpec spec = parse(root, errors);
        if (spec != null) {
            validateSemantics(spec, taskDir, errors);
        }
        if (!errors.isEmpty()) {
            throw new TaskSpecException(taskDir.toString(), errors);
        }
        return spec;
    }

    private static TaskSpec parse(JsonNode root, List<String> errors) {
        int schemaVersion = root.path("schema_version").asInt(1);
        if (schemaVersion != 1) {
            errors.add("schema_version 仅支持 1，实际为 " + schemaVersion);
        }
        String taskId = requiredText(root, "task_id", errors);
        String taskName = requiredText(root, "task_name", errors);
        TaskType taskType = parseEnum(root, "task_type", TaskType.class, errors);
        String agentBrief = requiredText(root, "agent_brief", errors);
        String description = root.path("description").asText("");

        List<String> visibleContext = new ArrayList<>();
        for (JsonNode item : root.path("visible_context")) {
            visibleContext.add(item.asText());
        }

        List<TaskSpec.AllowedTool> allowedTools = new ArrayList<>();
        for (JsonNode item : root.path("allowed_tools")) {
            String name = item.path("name").asText("");
            if (name.isBlank()) {
                errors.add("allowed_tools 中存在缺少 name 的条目");
                continue;
            }
            allowedTools.add(new TaskSpec.AllowedTool(name, item.path("description").asText("")));
        }

        JsonNode submitNode = root.path("submit");
        String defaultSchema = taskType == null ? "builtin:generic" : "builtin:" + taskType.jsonName();
        TaskSpec.Submit submit = new TaskSpec.Submit(
                submitNode.path("format").asText("json"),
                submitNode.path("schema").asText(defaultSchema),
                submitNode.path("max_attempts").asInt(3),
                submitNode.path("cooldown_seconds").asInt(0));

        JsonNode judgeNode = root.path("judge");
        JudgeType judgeType = parseEnum(judgeNode, "type", JudgeType.class, errors);
        JsonNode feedbackNode = judgeNode.path("feedback");
        FeedbackLevel level = feedbackNode.has("level")
                ? parseEnum(feedbackNode, "level", FeedbackLevel.class, errors)
                : FeedbackLevel.FAILED_RULES;
        TaskSpec.JudgeSpec judge = new TaskSpec.JudgeSpec(
                judgeType,
                judgeNode.path("rules_file").asText(null),
                judgeNode.path("script").asText(null),
                judgeNode.path("script_timeout_seconds").asInt(120),
                new TaskSpec.Feedback(level, feedbackNode.path("include_scores").asBoolean(true)));

        JsonNode scoringNode = root.path("scoring");
        List<TaskSpec.Dimension> dimensions = new ArrayList<>();
        for (JsonNode dim : scoringNode.path("dimensions")) {
            dimensions.add(new TaskSpec.Dimension(dim.path("name").asText(""), dim.path("weight").asInt(0)));
        }
        SelectionPolicy selection = scoringNode.has("selection")
                ? parseEnum(scoringNode, "selection", SelectionPolicy.class, errors)
                : SelectionPolicy.BEST_SCORE;
        TaskSpec.Scoring scoring = new TaskSpec.Scoring(
                scoringNode.path("max_score").asInt(100),
                scoringNode.path("pass_score").asInt(80),
                selection,
                dimensions);

        JsonNode runtimeNode = root.path("runtime");
        TaskSpec.RuntimeSpec runtime = new TaskSpec.RuntimeSpec(
                runtimeNode.path("timeout_minutes").asInt(30),
                runtimeNode.path("attempt_timeout_minutes").asInt(10),
                runtimeNode.path("allow_multi_submit").asBoolean(true),
                runtimeNode.path("auto_eval_interval_seconds").asInt(0),
                runtimeNode.path("resume_enabled").asBoolean(true));

        if (taskId == null || taskName == null || taskType == null || agentBrief == null || judgeType == null) {
            return null;
        }
        return new TaskSpec(schemaVersion, taskId, taskName, taskType, description, agentBrief,
                visibleContext, allowedTools, submit, judge, scoring, runtime);
    }

    private static void validateSemantics(TaskSpec spec, Path taskDir, List<String> errors) {
        if (!taskDir.getFileName().toString().equals(spec.taskId())) {
            errors.add("task_id (" + spec.taskId() + ") 必须与任务目录名 (" + taskDir.getFileName() + ") 一致");
        }
        if (!"json".equals(spec.submit().format())) {
            errors.add("submit.format 当前仅支持 json");
        }
        if (spec.submit().maxAttempts() < 1) {
            errors.add("submit.max_attempts 必须 ≥ 1");
        }
        if (spec.submit().cooldownSeconds() < 0) {
            errors.add("submit.cooldown_seconds 不能为负");
        }

        // 可见清单：必须位于 work/ 内且真实存在——instructions 中承诺给 Agent 的文件缺失即任务作废。
        for (String rel : spec.visibleContext()) {
            if (!rel.startsWith("work/")) {
                errors.add("visible_context 条目必须以 work/ 开头: " + rel);
            } else if (!Files.isRegularFile(taskDir.resolve(rel))) {
                errors.add("visible_context 引用的文件不存在: " + rel);
            }
        }
        if (!Files.isDirectory(taskDir.resolve("work"))) {
            errors.add("缺少 work/ 目录");
        }

        // judge 引用完整性。
        JudgeType type = spec.judge().type();
        if (type == JudgeType.RULES || type == JudgeType.HYBRID) {
            String rulesFile = spec.judge().rulesFile();
            if (rulesFile == null || rulesFile.isBlank()) {
                errors.add("judge.type=" + type.jsonName() + " 时必须提供 judge.rules_file");
            } else if (!Files.isRegularFile(taskDir.resolve(rulesFile))) {
                errors.add("judge.rules_file 不存在: " + rulesFile);
            }
        }
        if (type == JudgeType.SCRIPT || type == JudgeType.HYBRID) {
            String script = spec.judge().script();
            if (script == null || script.isBlank()) {
                errors.add("judge.type=" + type.jsonName() + " 时必须提供 judge.script");
            } else if (!Files.isRegularFile(taskDir.resolve(script))) {
                errors.add("judge.script 不存在: " + script);
            }
        }

        // 提交 schema 引用。
        String schema = spec.submit().schema();
        if (schema.startsWith("builtin:")) {
            String name = schema.substring("builtin:".length());
            if (TaskSpecLoader.class.getResource("/schemas/submission." + name + ".schema.json") == null) {
                errors.add("未知的内置提交 schema: " + schema);
            }
        } else if (!Files.isRegularFile(taskDir.resolve(schema))) {
            errors.add("submit.schema 引用的文件不存在: " + schema);
        }

        // 工具 mock 应答库（Phase 1 工具一律 mock，应答库缺失 = 任务不可运行）。
        for (TaskSpec.AllowedTool tool : spec.allowedTools()) {
            Path fixture = taskDir.resolve("hidden/tools/" + tool.name() + ".responses.yaml");
            if (!Files.isRegularFile(fixture)) {
                errors.add("工具 " + tool.name() + " 缺少 mock 应答库: hidden/tools/" + tool.name() + ".responses.yaml");
            }
        }

        // 计分配置。
        TaskSpec.Scoring scoring = spec.scoring();
        if (scoring.maxScore() <= 0) {
            errors.add("scoring.max_score 必须 > 0");
        }
        if (scoring.passScore() <= 0 || scoring.passScore() > scoring.maxScore()) {
            errors.add("scoring.pass_score 必须位于 (0, max_score] 区间");
        }
        if (scoring.dimensions().isEmpty()) {
            errors.add("scoring.dimensions 不能为空");
        }
        Set<String> names = new HashSet<>();
        int weightSum = 0;
        for (TaskSpec.Dimension dim : scoring.dimensions()) {
            if (dim.name().isBlank()) {
                errors.add("scoring.dimensions 存在空维度名");
            }
            if (!names.add(dim.name())) {
                errors.add("scoring.dimensions 维度名重复: " + dim.name());
            }
            if (dim.weight() <= 0) {
                errors.add("维度 " + dim.name() + " 的 weight 必须 > 0");
            }
            weightSum += dim.weight();
        }
        if (!scoring.dimensions().isEmpty() && weightSum != scoring.maxScore()) {
            errors.add("维度权重之和 (" + weightSum + ") 必须等于 max_score (" + scoring.maxScore() + ")");
        }

        // 运行时预算。
        if (spec.runtime().timeoutMinutes() <= 0 || spec.runtime().attemptTimeoutMinutes() <= 0) {
            errors.add("runtime.timeout_minutes / attempt_timeout_minutes 必须 > 0");
        }
    }

    private static String requiredText(JsonNode node, String field, List<String> errors) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            errors.add("缺少必填字段: " + field);
            return null;
        }
        return value.trim();
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode node, String field, Class<E> type, List<String> errors) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            errors.add("缺少必填字段: " + field);
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("字段 " + field + " 取值非法: " + value);
            return null;
        }
    }
}
