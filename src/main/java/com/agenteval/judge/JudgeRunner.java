package com.agenteval.judge;

import com.agenteval.Version;
import com.agenteval.task.JudgeType;
import com.agenteval.task.TaskSpec;
import com.agenteval.util.Hashes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评审编排器：按任务的 judge.type 分派到规则引擎 / 脚本评审，统一聚合计分。
 *
 * <p>计分模型：每个检查项向其维度贡献 (earned, possible)；
 * 维度得分 = 权重 × Σearned / Σpossible；总分 = Σ维度得分（1 位小数）。
 * 通过 = 总分 ≥ 通过线 <strong>且</strong> 无 blocking 检查失败（一票否决，
 * 与 agentScopeScaffold CompositeEvaluator 的 gate 语义同构）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class JudgeRunner {

    private JudgeRunner() {
    }

    /**
     * 执行完整评审并产出结构化结果。
     *
     * @param input 评审输入
     * @return 完整评分结果（私有版）
     * @throws JudgeException 评审设施故障时
     */
    public static JudgeResult judge(JudgeInput input) {
        TaskSpec spec = input.taskSpec();
        JudgeType type = spec.judge().type();
        Set<String> dimensionNames = spec.scoring().dimensions().stream()
                .map(TaskSpec.Dimension::name)
                .collect(Collectors.toSet());

        List<CheckOutcome> outcomes = new ArrayList<>();
        String judgeVersion = "";
        if (type == JudgeType.RULES || type == JudgeType.HYBRID) {
            RulesFile rules = RulesFile.load(
                    input.taskDir().resolve(spec.judge().rulesFile()), dimensionNames);
            judgeVersion = rules.judgeVersion();
            outcomes.addAll(RulesJudge.run(rules, input));
        }
        if (type == JudgeType.SCRIPT || type == JudgeType.HYBRID) {
            outcomes.addAll(ScriptJudge.run(input, dimensionNames));
        }
        return aggregate(input, outcomes, type, judgeVersion);
    }

    /**
     * 把检查结论聚合为评分结果（独立方法，供测试直接驱动）。
     *
     * <p>此重载不携带 {@code judge_version}，仅供不关心规则语义版本的测试使用；
     * 生产路径请用 {@link #aggregate(JudgeInput, List, JudgeType, String)}。
     *
     * @param input 评审输入
     * @param outcomes 全部检查结论
     * @param type 评审类型
     * @return 评分结果
     */
    public static JudgeResult aggregate(JudgeInput input, List<CheckOutcome> outcomes, JudgeType type) {
        return aggregate(input, outcomes, type, "");
    }

    /**
     * 把检查结论聚合为评分结果（携带规则语义版本）。
     *
     * @param input 评审输入
     * @param outcomes 全部检查结论
     * @param type 评审类型
     * @param judgeVersion 规则语义版本（来自 judge.rules.yaml；纯脚本评审可传空串）
     * @return 评分结果
     */
    public static JudgeResult aggregate(JudgeInput input, List<CheckOutcome> outcomes,
                                        JudgeType type, String judgeVersion) {
        TaskSpec spec = input.taskSpec();

        Map<String, double[]> byDimension = new LinkedHashMap<>();
        for (TaskSpec.Dimension dim : spec.scoring().dimensions()) {
            byDimension.put(dim.name(), new double[]{0, 0});
        }
        for (CheckOutcome outcome : outcomes) {
            double[] acc = byDimension.get(outcome.dimension());
            if (acc == null) {
                throw new JudgeException("check " + outcome.id() + " 的维度未声明: " + outcome.dimension());
            }
            acc[0] += outcome.pointsEarned();
            acc[1] += outcome.pointsPossible();
        }

        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        double total = 0;
        for (TaskSpec.Dimension dim : spec.scoring().dimensions()) {
            double[] acc = byDimension.get(dim.name());
            double score = acc[1] == 0 ? 0 : dim.weight() * acc[0] / acc[1];
            score = Math.round(score * 10) / 10.0;
            dimensionScores.put(dim.name(), score);
            total += score;
        }
        double score = Math.round(total * 10) / 10.0;

        List<String> passedRules = outcomes.stream()
                .filter(CheckOutcome::passed)
                .map(CheckOutcome::id)
                .toList();
        List<JudgeResult.FailedRule> failedRules = outcomes.stream()
                .filter(o -> !o.passed())
                .map(o -> new JudgeResult.FailedRule(
                        o.id(),
                        o.externalMessage() == null || o.externalMessage().isBlank()
                                ? "该项检查未通过" : o.externalMessage(),
                        o.severity(),
                        o.dimension(),
                        Math.round((o.pointsPossible() - o.pointsEarned()) * 100) / 100.0,
                        o.blocking()))
                .toList();

        boolean blockingFailed = outcomes.stream().anyMatch(o -> !o.passed() && o.blocking());
        boolean passed = !blockingFailed && score >= spec.scoring().passScore();

        JudgeResult.Reproducibility repro = new JudgeResult.Reproducibility(
                Version.ENGINE,
                judgeVersion == null ? "" : judgeVersion,
                Hashes.sha256OfDir(input.hiddenDir()),
                Hashes.sha256OfFile(input.submissionFile()),
                Hashes.sha256OfDir(input.workspaceDir()),
                Instant.now(),
                type == JudgeType.RULES);

        return new JudgeResult(1, spec.taskId(), input.runId(), input.attemptId(),
                type.jsonName(), score, spec.scoring().maxScore(), passed,
                dimensionScores, passedRules, failedRules,
                buildFeedbackText(spec, score, passed, blockingFailed, failedRules),
                RulesJudge.privateNotes(outcomes), repro);
    }

    private static String buildFeedbackText(TaskSpec spec, double score, boolean passed,
                                            boolean blockingFailed, List<JudgeResult.FailedRule> failedRules) {
        StringBuilder sb = new StringBuilder();
        sb.append("得分 ").append(score).append("/").append(spec.scoring().maxScore());
        if (passed) {
            sb.append("，已通过。");
        } else {
            sb.append("，未达通过线 ").append(spec.scoring().passScore()).append("。");
            if (blockingFailed) {
                sb.append("存在一票否决项未通过。");
            }
            List<String> hints = failedRules.stream()
                    .map(JudgeResult.FailedRule::message)
                    .filter(m -> !m.isBlank())
                    .distinct()
                    .limit(5)
                    .toList();
            if (!hints.isEmpty()) {
                sb.append("改进方向：").append(String.join("；", hints));
            }
        }
        return sb.toString();
    }
}
