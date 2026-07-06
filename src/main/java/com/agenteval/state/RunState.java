package com.agenteval.state;

import java.time.Instant;
import java.util.List;

/**
 * resume 快照（{@code run_state.json}）：每个 attempt 结束后原子更新，
 * 进程被杀后凭它 + trace 精确恢复进度（轻量 Auto-Resume）。
 *
 * @param schemaVersion 快照 schema 版本（固定 1）
 * @param runId run id
 * @param taskId 任务 id
 * @param status 当前状态
 * @param statusReason 终态原因（max_attempts_reached / timeout / agent_exhausted 等）
 * @param startedAt run 开始时间
 * @param updatedAt 最近更新时间
 * @param attempts 已完成的 attempt 记录（时间序）
 * @param bestAttemptId 按 selection 策略选出的最佳 attempt（终态时非空）
 * @param hiddenFingerprintBefore run 开始时的 hidden 指纹（完整性基准）
 * @param workspaceFingerprintInitial workspace 初始指纹
 * @author shiyongyin
 * @since 0.1.0
 */
public record RunState(
        int schemaVersion,
        String runId,
        String taskId,
        RunStatus status,
        String statusReason,
        Instant startedAt,
        Instant updatedAt,
        List<AttemptRecord> attempts,
        String bestAttemptId,
        String hiddenFingerprintBefore,
        String workspaceFingerprintInitial) {

    public RunState {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    /**
     * 单个 attempt 的结果摘要（完整评分在 judge/ 目录，这里只留 resume 与报告所需字段）。
     *
     * @param attemptId attempt id
     * @param valid 提交是否通过 schema 校验
     * @param score 得分（无效提交为 {@code null}）
     * @param passed 是否通过
     * @param blockingFailures 一票否决失败数
     * @param failedRuleIds 失败检查项 id
     * @param needsHumanReview 是否声明需人工复核
     * @param durationMs 本轮耗时（agent 执行 + 评审）
     * @param judgedAt 评审时间（无效提交为收件时间）
     */
    public record AttemptRecord(
            String attemptId,
            boolean valid,
            Double score,
            boolean passed,
            int blockingFailures,
            List<String> failedRuleIds,
            boolean needsHumanReview,
            long durationMs,
            Instant judgedAt) {

        public AttemptRecord {
            failedRuleIds = failedRuleIds == null ? List.of() : List.copyOf(failedRuleIds);
        }
    }

    /**
     * 追加一条 attempt 记录并刷新时间戳。
     *
     * @param record 新记录
     * @return 新快照
     */
    public RunState withAttempt(AttemptRecord record) {
        List<AttemptRecord> next = new java.util.ArrayList<>(attempts);
        next.add(record);
        return new RunState(schemaVersion, runId, taskId, status, statusReason,
                startedAt, Instant.now(), next, bestAttemptId,
                hiddenFingerprintBefore, workspaceFingerprintInitial);
    }

    /**
     * 迁移到终态。
     *
     * @param newStatus 终态
     * @param reason 原因
     * @param best 最佳 attempt id（可为 {@code null}）
     * @return 新快照
     */
    public RunState finish(RunStatus newStatus, String reason, String best) {
        return new RunState(schemaVersion, runId, taskId, newStatus, reason,
                startedAt, Instant.now(), attempts, best,
                hiddenFingerprintBefore, workspaceFingerprintInitial);
    }

    /** @return 下一轮 attempt 序号（1 起） */
    public int nextAttemptNumber() {
        return attempts.size() + 1;
    }
}
