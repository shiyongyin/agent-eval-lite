package com.agenteval.trace;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * trace 事件类型全集。事件是框架各组件的行为留痕，Agent 不可读取。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public enum TraceEventType {

    /** run 生命周期开始（含任务/agent/指纹元数据）。 */
    RUN_STARTED,
    /** 一次 agent attempt 启动。 */
    AGENT_STARTED,
    /** 一次 agent attempt 结束（含退出码与日志引用）。 */
    AGENT_FINISHED,
    /** 经框架网关的工具调用（含 call_id 与 mock 标记）。 */
    TOOL_CALL,
    /** 框架代跑的 shell 命令（judge command 型 check 等）。 */
    SHELL_COMMAND,
    /** 收到提交文件。 */
    SUBMISSION_RECEIVED,
    /** 提交未通过 schema 校验。 */
    SUBMISSION_INVALID,
    /** 评审开始。 */
    JUDGE_STARTED,
    /** 评审完成（含分数与指纹）。 */
    JUDGE_COMPLETED,
    /** auto-eval 后台快照采样结果（kind=auto，不回注 Agent，只供轨迹观测）。 */
    AUTO_EVAL_SAMPLED,
    /** 受控反馈已写出。 */
    FEEDBACK_DELIVERED,
    /** Agent 过早自称完成、被要求继续（轻量 stop hook）。 */
    STOP_HOOK_TRIGGERED,
    /** 从 run_state 恢复继续执行。 */
    RESUME,
    /** 框架内部错误。 */
    ERROR,
    /** token / 成本记录（可选，取决于 agent 能否导出）。 */
    USAGE_RECORDED,
    /** 最佳 attempt 选择结果。 */
    FINAL_SELECTION,
    /** run 生命周期结束。 */
    RUN_COMPLETED;

    /**
     * 序列化为小写形式（trace.jsonl 中的书写形态）。
     *
     * @return 小写枚举名
     */
    @JsonValue
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
