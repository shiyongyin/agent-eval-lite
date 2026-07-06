package com.agenteval.judge;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 隐藏评审规则文件（{@code hidden/judge.rules.yaml}）的强类型形态。
 *
 * <p>规则文件是 Agent 永不可见的评分事实来源；{@code judge_version} 由任务作者在语义变化时
 * 手工递增，与目录指纹一起写进评分结果，保证「哪一版规则评的」可追溯。
 *
 * @param schemaVersion 文件 schema 版本（当前固定 1）
 * @param judgeVersion 规则语义版本（人工维护）
 * @param canaryToken 泄露探针 token（可选；出现在提交/工作区即视为越界读取）
 * @param checks 检查项清单
 * @author shiyongyin
 * @since 0.1.0
 */
public record RulesFile(int schemaVersion, String judgeVersion, String canaryToken, List<CheckDef> checks) {

    /** Phase 1 支持的全部 check 类型。 */
    public static final Set<String> SUPPORTED_TYPES = Set.of(
            "json_schema", "jsonpath_equals", "jsonpath_exists", "jsonpath_matches",
            "list_coverage", "evidence_sources_valid",
            "workspace_file_exists", "workspace_file_contains", "changed_files_verified",
            "command", "tool_call_required", "tool_call_forbidden", "no_canary_leak",
            "world_state");

    public RulesFile {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /**
     * 单个检查项定义。类型专属参数保留在 {@code raw} 节点中，由检查引擎按类型读取。
     *
     * @param id 检查项唯一标识
     * @param dimension 归属计分维度（必须存在于 task.yaml 的 scoring.dimensions）
     * @param points 满分点数
     * @param blocking 是否一票否决（失败即整体 fail，借鉴 CompositeEvaluator 语义）
     * @param type 检查类型（{@link #SUPPORTED_TYPES}）
     * @param severity 严重度（进入 failed_rules，供报告统计）
     * @param feedbackPass 通过时的对外文案（可选）
     * @param feedbackFail 失败时的对外文案（回传 Agent 的内容，禁止包含 expected 值）
     * @param raw 原始节点（类型专属参数）
     */
    public record CheckDef(
            String id,
            String dimension,
            double points,
            boolean blocking,
            String type,
            String severity,
            String feedbackPass,
            String feedbackFail,
            JsonNode raw) {
    }

    /**
     * 加载并校验规则文件。
     *
     * @param rulesFile 规则 YAML 路径
     * @param dimensionNames 任务声明的合法维度名集合
     * @return 规则文件模型
     * @throws JudgeException 文件非法时
     */
    public static RulesFile load(Path rulesFile, Set<String> dimensionNames) {
        JsonNode root;
        try {
            root = Jsons.yaml().readTree(Files.readString(rulesFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JudgeException("规则文件读取失败: " + rulesFile, e);
        }
        List<String> errors = new ArrayList<>();
        int schemaVersion = root.path("schema_version").asInt(1);
        String judgeVersion = root.path("judge_version").asText("");
        if (judgeVersion.isBlank()) {
            errors.add("缺少 judge_version");
        }
        String canary = root.path("canary_token").asText(null);

        List<CheckDef> checks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : root.path("checks")) {
            String id = node.path("id").asText("");
            String type = node.path("type").asText("");
            String dimension = node.path("dimension").asText("");
            if (id.isBlank()) {
                errors.add("存在缺少 id 的 check");
                continue;
            }
            if (!ids.add(id)) {
                errors.add("check id 重复: " + id);
            }
            if (!SUPPORTED_TYPES.contains(type)) {
                errors.add("check " + id + " 的 type 不受支持: " + type);
            }
            if (!dimensionNames.contains(dimension)) {
                errors.add("check " + id + " 的 dimension 未在 scoring.dimensions 中声明: " + dimension);
            }
            double points = node.path("points").asDouble(0);
            if (points < 0) {
                errors.add("check " + id + " 的 points 不能为负");
            }
            String severity = node.path("severity").asText("medium");
            if (!Set.of("low", "medium", "high", "critical").contains(severity)) {
                errors.add("check " + id + " 的 severity 非法: " + severity);
            }
            checks.add(new CheckDef(id, dimension, points,
                    node.path("blocking").asBoolean(false), type, severity,
                    node.path("feedback_pass").asText(null),
                    node.path("feedback_fail").asText(null),
                    node));
        }
        if (checks.isEmpty()) {
            errors.add("checks 不能为空");
        }
        if (!errors.isEmpty()) {
            throw new JudgeException("规则文件非法 [" + rulesFile + "]:\n  - " + String.join("\n  - ", errors));
        }
        return new RulesFile(schemaVersion, judgeVersion, canary, checks);
    }
}
