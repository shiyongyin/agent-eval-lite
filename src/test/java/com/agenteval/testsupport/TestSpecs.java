package com.agenteval.testsupport;

import com.agenteval.task.FeedbackLevel;
import com.agenteval.task.JudgeType;
import com.agenteval.task.SelectionPolicy;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskType;
import java.util.List;

/**
 * 测试用 TaskSpec 构造器：绕过 YAML 直接构造合法规格，让判分测试聚焦规则本身。
 */
public final class TestSpecs {

    private TestSpecs() {
    }

    /**
     * 构造单维度（total=100）的最小规格。
     *
     * @param taskId 任务 id
     * @return 规格
     */
    public static TaskSpec singleDimension(String taskId) {
        return withDimensions(taskId, List.of(new TaskSpec.Dimension("main", 100)));
    }

    /**
     * 构造指定维度的最小规格（rules judge、通过线 80）。
     *
     * @param taskId 任务 id
     * @param dimensions 维度清单
     * @return 规格
     */
    public static TaskSpec withDimensions(String taskId, List<TaskSpec.Dimension> dimensions) {
        return new TaskSpec(1, taskId, "测试任务", TaskType.GENERIC, "", "测试任务简报",
                List.of(), List.of(),
                new TaskSpec.Submit("json", "builtin:generic", 3, 0),
                new TaskSpec.JudgeSpec(JudgeType.RULES, "hidden/judge.rules.yaml", null, 120,
                        new TaskSpec.Feedback(FeedbackLevel.FAILED_RULES, true)),
                new TaskSpec.Scoring(100, 80, SelectionPolicy.BEST_SCORE, dimensions),
                new TaskSpec.RuntimeSpec(30, 10, true, 0, true));
    }
}
