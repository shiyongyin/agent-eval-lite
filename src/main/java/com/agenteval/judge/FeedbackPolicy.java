package com.agenteval.judge;

import com.agenteval.task.FeedbackLevel;
import com.agenteval.task.TaskSpec;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 受控反馈策略：完整评分结果 → 按任务配置裁剪出回传 Agent 的版本。
 *
 * <p>这是 Work/Judge 隔离在信息面上的最后一道闸：无论粒度多细，
 * {@code private_notes}（含 expected 值、命中细节）永不出闸。裁剪逐字段白名单构造，
 * 不做「复制后删除」——新加字段默认不外泄。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class FeedbackPolicy {

    private FeedbackPolicy() {
    }

    /**
     * 写出「提交有效」场景的受控反馈。
     *
     * @param feedbackDir 反馈目录
     * @param spec 任务规格
     * @param result 完整评分结果
     * @param nextAttemptId 下一轮应使用的 attempt id（无剩余轮次时为 {@code null}）
     * @return 反馈文件路径
     */
    public static Path writeJudged(Path feedbackDir, TaskSpec spec, JudgeResult result, String nextAttemptId) {
        FeedbackLevel level = spec.judge().feedback().level();
        ObjectNode node = Jsons.json().createObjectNode();
        node.put("schema_version", 1);
        node.put("attempt_id", result.attemptId());
        node.put("valid", true);
        node.put("score", result.score());
        node.put("max_score", result.maxScore());
        node.put("passed", result.passed());
        if (spec.judge().feedback().includeScores()) {
            node.set("dimension_scores", Jsons.json().valueToTree(result.dimensionScores()));
        }
        if (level == FeedbackLevel.FAILED_RULES || level == FeedbackLevel.FULL) {
            node.put("feedback", result.feedbackToAgent());
            ArrayNode failed = node.putArray("failed_checks");
            for (JudgeResult.FailedRule rule : result.failedRules()) {
                ObjectNode item = failed.addObject();
                item.put("dimension", rule.dimension());
                item.put("severity", rule.severity());
                item.put("message", rule.message());
                if (level == FeedbackLevel.FULL) {
                    item.put("rule_id", rule.ruleId());
                    item.put("points_lost", rule.pointsLost());
                    item.put("blocking", rule.blocking());
                }
            }
        }
        if (level == FeedbackLevel.FULL) {
            node.set("passed_rules", Jsons.json().valueToTree(result.passedRules()));
        }
        appendNextStep(node, result.passed(), nextAttemptId);
        return write(feedbackDir, result.attemptId(), node);
    }

    /**
     * 写出「提交无效」场景的受控反馈（schema 错误帮 Agent 修格式，不涉评分规则）。
     *
     * @param feedbackDir 反馈目录
     * @param attemptId 本轮 attempt id
     * @param errors schema 校验错误
     * @param nextAttemptId 下一轮 attempt id（无剩余轮次时为 {@code null}）
     * @return 反馈文件路径
     */
    public static Path writeInvalid(Path feedbackDir, String attemptId, List<String> errors, String nextAttemptId) {
        ObjectNode node = Jsons.json().createObjectNode();
        node.put("schema_version", 1);
        node.put("attempt_id", attemptId);
        node.put("valid", false);
        node.put("feedback", "提交未通过格式校验，本轮不计分。请修正后重新提交。");
        node.set("schema_errors", Jsons.json().valueToTree(errors));
        appendNextStep(node, false, nextAttemptId);
        return write(feedbackDir, attemptId, node);
    }

    private static void appendNextStep(ObjectNode node, boolean passed, String nextAttemptId) {
        if (passed) {
            node.put("next_step", "任务已通过，无需再提交。");
        } else if (nextAttemptId != null) {
            node.put("next_step", "请将修正后的提交写入 inbox/" + nextAttemptId + ".json");
            node.put("next_attempt_id", nextAttemptId);
        } else {
            node.put("next_step", "提交次数已用尽。");
        }
    }

    private static Path write(Path feedbackDir, String attemptId, ObjectNode node) {
        try {
            Files.createDirectories(feedbackDir);
            Path file = feedbackDir.resolve(attemptId + ".feedback.json");
            Files.writeString(file, node.toPrettyString(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("写入反馈失败", e);
        }
    }
}
