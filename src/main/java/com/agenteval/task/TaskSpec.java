package com.agenteval.task;

import java.util.List;

/**
 * 任务规格（task.yaml 的强类型形态）——一次评估的单一事实来源。
 *
 * <p>本 record 只承载「已通过 {@link TaskSpecLoader} 校验与默认值归一」的合法配置；
 * 所有字段在构造后不可变。task.yaml 本身对 Agent 可见（instructions 由其渲染），
 * 因此这里<strong>不允许</strong>出现任何答案性信息——expected 数据、规则细节一律放 hidden/。
 *
 * @param schemaVersion 规格 schema 版本（当前固定 1）
 * @param taskId 任务唯一标识（必须与任务目录名一致）
 * @param taskName 人类可读任务名
 * @param taskType 任务类型
 * @param description 给评估者看的任务背景（不渲染给 Agent）
 * @param agentBrief 渲染进 instructions.md 的任务陈述（Agent 视角）
 * @param visibleContext 向 Agent 声明的可读文件清单（work/ 内相对路径）
 * @param allowedTools 允许经框架工具网关调用的工具（空=不经网关）
 * @param submit 提交契约配置
 * @param judge 评审配置
 * @param scoring 计分配置
 * @param runtime 运行时预算配置
 * @author shiyongyin
 * @since 0.1.0
 */
public record TaskSpec(
        int schemaVersion,
        String taskId,
        String taskName,
        TaskType taskType,
        String description,
        String agentBrief,
        List<String> visibleContext,
        List<AllowedTool> allowedTools,
        Submit submit,
        JudgeSpec judge,
        Scoring scoring,
        RuntimeSpec runtime) {

    public TaskSpec {
        visibleContext = visibleContext == null ? List.of() : List.copyOf(visibleContext);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    /**
     * 允许调用的工具声明。
     *
     * @param name 工具名（如 {@code user.lookup}）
     * @param description 用途描述（渲染进 instructions）
     */
    public record AllowedTool(String name, String description) {
    }

    /**
     * 提交契约配置。
     *
     * @param format 提交格式（当前仅 {@code json}）
     * @param schema 分型 schema：{@code builtin:<type>} 或 hidden/ 内的 schema 文件相对路径
     * @param maxAttempts 最大提交次数（含无效提交）
     * @param cooldownSeconds 两次提交之间的最小间隔秒数（防反馈试探刷分）
     */
    public record Submit(String format, String schema, int maxAttempts, int cooldownSeconds) {
    }

    /**
     * 评审配置。
     *
     * @param type judge 类型
     * @param rulesFile 规则文件相对路径（type=rules|hybrid 必填）
     * @param script 评分脚本相对路径（type=script|hybrid 必填）
     * @param scriptTimeoutSeconds 脚本执行超时秒数
     * @param feedback 反馈粒度配置
     */
    public record JudgeSpec(
            JudgeType type,
            String rulesFile,
            String script,
            int scriptTimeoutSeconds,
            Feedback feedback) {
    }

    /**
     * 反馈粒度配置。
     *
     * @param level 粒度级别
     * @param includeScores 是否携带维度分
     */
    public record Feedback(FeedbackLevel level, boolean includeScores) {
    }

    /**
     * 计分维度。
     *
     * @param name 维度名（judge 规则中的 dimension 必须引用于此）
     * @param weight 权重分值（全部维度之和必须等于 maxScore）
     */
    public record Dimension(String name, int weight) {
    }

    /**
     * 计分配置。
     *
     * @param maxScore 满分
     * @param passScore 通过线
     * @param selection 最佳 attempt 选择策略
     * @param dimensions 维度清单
     */
    public record Scoring(int maxScore, int passScore, SelectionPolicy selection, List<Dimension> dimensions) {

        public Scoring {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }

        /**
         * 按维度名取权重。
         *
         * @param name 维度名
         * @return 权重；维度不存在时返回 0
         */
        public int weightOf(String name) {
            return dimensions.stream()
                    .filter(d -> d.name().equals(name))
                    .mapToInt(Dimension::weight)
                    .findFirst()
                    .orElse(0);
        }
    }

    /**
     * 运行时预算配置。
     *
     * @param timeoutMinutes 整个 run 的墙钟预算（分钟）
     * @param attemptTimeoutMinutes 单次 agent 调用预算（分钟）
     * @param allowMultiSubmit 是否允许多轮提交
     * @param autoEvalIntervalSeconds 后台采样间隔秒数（0=关闭；Phase 3 生效）
     * @param resumeEnabled 是否允许 --resume 续跑
     */
    public record RuntimeSpec(
            int timeoutMinutes,
            int attemptTimeoutMinutes,
            boolean allowMultiSubmit,
            int autoEvalIntervalSeconds,
            boolean resumeEnabled) {
    }
}
