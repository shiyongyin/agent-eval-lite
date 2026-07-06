package com.agenteval.report;

import com.agenteval.state.RunState;
import com.agenteval.task.SelectionPolicy;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 最佳 attempt 选择器：按任务声明的策略从全部 attempt 中挑出计入总结的那一次。
 *
 * <p>只有「有效且已评分」的 attempt 参与选择——无效提交连入围资格都没有。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class BestAttemptSelector {

    private BestAttemptSelector() {
    }

    /**
     * 选择最佳 attempt。
     *
     * @param attempts 全部 attempt 记录（时间序）
     * @param policy 选择策略
     * @return 最佳记录；无有效 attempt 时为空
     */
    public static Optional<RunState.AttemptRecord> select(
            List<RunState.AttemptRecord> attempts, SelectionPolicy policy) {
        List<RunState.AttemptRecord> candidates = attempts.stream()
                .filter(a -> a.valid() && a.score() != null)
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return switch (policy) {
            // max 取首个最大值 → 同分自然偏向更早的 attempt。
            case BEST_SCORE -> candidates.stream()
                    .max(Comparator.comparingDouble(RunState.AttemptRecord::score));
            case FIRST_PASS -> candidates.stream()
                    .filter(RunState.AttemptRecord::passed)
                    .findFirst()
                    .or(() -> select(attempts, SelectionPolicy.BEST_SCORE));
            case LAST -> Optional.of(candidates.get(candidates.size() - 1));
        };
    }
}
