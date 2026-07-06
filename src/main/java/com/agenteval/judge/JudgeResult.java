package com.agenteval.judge;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评审输出（完整私有版）：结构化评分 + 可复现指纹。
 *
 * <p>本对象整体落盘于 {@code judge/}（Agent 禁区）；回传 Agent 的受控裁剪版由
 * {@link FeedbackPolicy} 按任务配置生成——{@code privateNotes} 与 expected 细节永不回传。
 *
 * @param schemaVersion 结果 schema 版本（固定 1）
 * @param taskId 任务 id
 * @param runId run id
 * @param attemptId attempt id
 * @param judgeType 评审类型（rules/script/hybrid）
 * @param score 总分（1 位小数）
 * @param maxScore 满分
 * @param passed 是否通过（分数达标且无 blocking 失败）
 * @param dimensionScores 各维度得分（按任务声明顺序）
 * @param passedRules 通过的检查项 id
 * @param failedRules 失败的检查项明细
 * @param feedbackToAgent 面向 Agent 的总体反馈文案
 * @param privateNotes 仅评估人员可见的备注（永不回传 Agent）
 * @param reproducibility 可复现指纹组
 * @author shiyongyin
 * @since 0.1.0
 */
public record JudgeResult(
        int schemaVersion,
        String taskId,
        String runId,
        String attemptId,
        String judgeType,
        double score,
        int maxScore,
        boolean passed,
        Map<String, Double> dimensionScores,
        List<String> passedRules,
        List<FailedRule> failedRules,
        String feedbackToAgent,
        String privateNotes,
        Reproducibility reproducibility) {

    /**
     * 失败检查项明细。
     *
     * @param ruleId 检查项 id
     * @param message 对外文案（feedback_fail；不含 expected 值）
     * @param severity 严重度
     * @param dimension 归属维度
     * @param pointsLost 失分（possible - earned）
     * @param blocking 是否一票否决项
     */
    public record FailedRule(
            String ruleId, String message, String severity,
            String dimension, double pointsLost, boolean blocking) {
    }

    /**
     * 可复现指纹组：事后审计「评的是什么、用哪版规则、哪个引擎评的」。
     *
     * <p>{@code judgeVersion} 是任务作者在 {@code hidden/judge.rules.yaml} 里人工维护的规则语义版本，
     * 与目录指纹互补：指纹回答「字节层面是否同一份规则」，版本回答「作者声明的第几版规则语义」。
     * 二者一并落盘，规则语义升级后历史分数才可被正确区分与追溯。
     *
     * @param engineVersion 引擎版本
     * @param judgeVersion 规则语义版本（来自 judge.rules.yaml 的 judge_version；纯脚本评审时为空串）
     * @param judgeRulesFingerprint hidden 目录树 SHA-256
     * @param submissionFingerprint 提交文件 SHA-256
     * @param workspaceFingerprint 判分时刻 workspace 目录树 SHA-256
     * @param judgedAt 判分时间
     * @param deterministic 是否确定性评审（rules=true；含 script/llm 视实现而定）
     */
    public record Reproducibility(
            String engineVersion,
            String judgeVersion,
            String judgeRulesFingerprint,
            String submissionFingerprint,
            String workspaceFingerprint,
            Instant judgedAt,
            boolean deterministic) {
    }
}
