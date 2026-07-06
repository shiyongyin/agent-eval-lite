package com.agenteval.judge;

/**
 * 单个检查项的执行结论——rules 引擎与 script judge 的公共货币，
 * 由 {@link JudgeRunner} 统一聚合计分。
 *
 * @param id 检查项 id
 * @param dimension 归属维度
 * @param pointsEarned 实得点数（支持部分得分，如覆盖率类检查）
 * @param pointsPossible 满分点数
 * @param passed 是否通过
 * @param blocking 是否一票否决项
 * @param severity 严重度
 * @param message 内部诊断信息（进入 private_notes，可含命中细节）
 * @param externalMessage 对外文案（feedback_fail / feedback_pass，回传 Agent 用）
 * @author shiyongyin
 * @since 0.1.0
 */
public record CheckOutcome(
        String id,
        String dimension,
        double pointsEarned,
        double pointsPossible,
        boolean passed,
        boolean blocking,
        String severity,
        String message,
        String externalMessage) {

    /**
     * 构造通过结论。
     *
     * @param def 检查定义
     * @param message 内部诊断信息
     * @return 满分通过结论
     */
    public static CheckOutcome pass(RulesFile.CheckDef def, String message) {
        return new CheckOutcome(def.id(), def.dimension(), def.points(), def.points(),
                true, def.blocking(), def.severity(), message,
                def.feedbackPass() == null ? "" : def.feedbackPass());
    }

    /**
     * 构造失败结论（零分）。
     *
     * @param def 检查定义
     * @param message 内部诊断信息
     * @return 零分失败结论
     */
    public static CheckOutcome fail(RulesFile.CheckDef def, String message) {
        return partial(def, 0, false, message);
    }

    /**
     * 构造部分得分结论（覆盖率类检查用）。
     *
     * @param def 检查定义
     * @param earned 实得点数
     * @param passed 是否达到通过门槛
     * @param message 内部诊断信息
     * @return 部分得分结论
     */
    public static CheckOutcome partial(RulesFile.CheckDef def, double earned, boolean passed, String message) {
        String external = passed
                ? (def.feedbackPass() == null ? "" : def.feedbackPass())
                : (def.feedbackFail() == null ? "该项检查未通过" : def.feedbackFail());
        return new CheckOutcome(def.id(), def.dimension(), earned, def.points(),
                passed, def.blocking(), def.severity(), message, external);
    }
}
