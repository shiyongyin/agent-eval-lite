package com.agenteval.state;

/**
 * run 的生命周期状态。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public enum RunStatus {

    /** 进行中（可被 resume）。 */
    RUNNING,
    /** 最佳 attempt 达到通过线且无一票否决失败。 */
    PASSED,
    /** 正常结束但未通过（次数用尽 / 超时 / agent 放弃）。 */
    FAILED,
    /** Agent 声明需要人工复核，评估挂起等待人类。 */
    PENDING_HUMAN,
    /** hidden 目录指纹在 run 期间发生变化，结果不可信。 */
    INTEGRITY_BROKEN,
    /** 评审设施故障（非 Agent 责任），结果无分数。 */
    ERROR
}
