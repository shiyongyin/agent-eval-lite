package com.agenteval.task;

import java.util.List;
import java.util.Map;

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
 * @param tier 任务分层（批跑意图元数据，供 {@code suite --tier} 过滤；不影响判分）
 * @param labels 自由标签（便于人工检索与筛选；不影响判分）
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
        RuntimeSpec runtime,
        TaskTier tier,
        List<String> labels) {

    public TaskSpec {
        visibleContext = visibleContext == null ? List.of() : List.copyOf(visibleContext);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        tier = tier == null ? TaskTier.REGRESSION : tier;
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /**
     * 兼容构造器：未声明分层元数据的历史调用方沿用旧签名，分层默认
     * {@link TaskTier#REGRESSION}、标签为空。
     *
     * @param schemaVersion 规格 schema 版本
     * @param taskId 任务唯一标识
     * @param taskName 人类可读任务名
     * @param taskType 任务类型
     * @param description 评估者视角背景
     * @param agentBrief 渲染给 Agent 的任务陈述
     * @param visibleContext 可读文件清单
     * @param allowedTools 允许的工具
     * @param submit 提交契约配置
     * @param judge 评审配置
     * @param scoring 计分配置
     * @param runtime 运行时预算配置
     */
    public TaskSpec(int schemaVersion, String taskId, String taskName, TaskType taskType,
                    String description, String agentBrief, List<String> visibleContext,
                    List<AllowedTool> allowedTools, Submit submit, JudgeSpec judge,
                    Scoring scoring, RuntimeSpec runtime) {
        this(schemaVersion, taskId, taskName, taskType, description, agentBrief,
                visibleContext, allowedTools, submit, judge, scoring, runtime,
                TaskTier.REGRESSION, List.of());
    }

    /**
     * 允许调用的工具声明。
     *
     * <p>{@code backend} 为可选的真实 HTTP 后端声明（Phase 3）：未声明时工具只有 mock 应答库
     * 一条通道；声明后，运行方可用 {@code AEL_TOOL_MODE=live} 让网关真实外呼并存档响应。
     * 白名单语义：Agent 的调用入参永远只作为请求负载，<strong>无法改变外呼目标</strong>——
     * 可达的 URL 只有任务作者在此声明的这一个。
     *
     * @param name 工具名（如 {@code user.lookup}）
     * @param description 用途描述（渲染进 instructions）
     * @param backend 真实 HTTP 后端声明（可为 {@code null}=纯 mock 工具）
     */
    public record AllowedTool(String name, String description, HttpBackend backend) {

        /**
         * 兼容构造器：纯 mock 工具（无真实后端）。
         *
         * @param name 工具名
         * @param description 用途描述
         */
        public AllowedTool(String name, String description) {
            this(name, description, null);
        }
    }

    /**
     * 工具的真实 HTTP 后端声明（task.yaml 的 {@code allowed_tools[].backend}，{@code type: http}）。
     *
     * <p>安全口径：URL / method / headers 全部由任务作者静态声明，Agent 入参只充当请求体
     * （POST）或查询参数（GET），不存在任何「入参拼进 URL」的通道。header 值支持
     * {@code ${ENV:NAME}} 占位符——凭证从框架进程环境变量解析，永不写进任务文件或渲染给 Agent。
     *
     * @param url 后端完整 URL（唯一可达目标，即白名单本身）
     * @param method HTTP 方法（{@code POST}=入参作 JSON 请求体；{@code GET}=入参顶层标量作查询参数）
     * @param headers 附加请求头（值可含 {@code ${ENV:NAME}} 占位符）
     * @param timeoutSeconds 单次外呼超时秒数
     */
    public record HttpBackend(String url, String method, Map<String, String> headers, int timeoutSeconds) {

        public HttpBackend {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
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
