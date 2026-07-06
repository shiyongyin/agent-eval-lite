package com.agenteval.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.state.RunState;
import com.agenteval.task.SelectionPolicy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link BestAttemptSelector} 三种策略的选择行为测试。
 */
class BestAttemptSelectorTest {

    private static RunState.AttemptRecord record(String id, boolean valid, Double score, boolean passed) {
        return new RunState.AttemptRecord(id, valid, score, passed, 0, List.of(), false, 100, Instant.now());
    }

    private final List<RunState.AttemptRecord> attempts = List.of(
            record("attempt_001", false, null, false),
            record("attempt_002", true, 70.0, false),
            record("attempt_003", true, 92.0, true),
            record("attempt_004", true, 92.0, true),
            record("attempt_005", true, 85.0, true));

    @Test
    void bestScore同分取更早的() {
        assertThat(BestAttemptSelector.select(attempts, SelectionPolicy.BEST_SCORE))
                .map(RunState.AttemptRecord::attemptId).hasValue("attempt_003");
    }

    @Test
    void firstPass取第一个通过的() {
        assertThat(BestAttemptSelector.select(attempts, SelectionPolicy.FIRST_PASS))
                .map(RunState.AttemptRecord::attemptId).hasValue("attempt_003");
    }

    @Test
    void firstPass无人通过时退回bestScore() {
        List<RunState.AttemptRecord> none = List.of(
                record("attempt_001", true, 40.0, false),
                record("attempt_002", true, 66.0, false));
        assertThat(BestAttemptSelector.select(none, SelectionPolicy.FIRST_PASS))
                .map(RunState.AttemptRecord::attemptId).hasValue("attempt_002");
    }

    @Test
    void last取最后一个有效的() {
        assertThat(BestAttemptSelector.select(attempts, SelectionPolicy.LAST))
                .map(RunState.AttemptRecord::attemptId).hasValue("attempt_005");
    }

    @Test
    void 全部无效时为空() {
        assertThat(BestAttemptSelector.select(
                List.of(record("attempt_001", false, null, false)), SelectionPolicy.BEST_SCORE))
                .isEmpty();
    }
}
