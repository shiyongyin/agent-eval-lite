package com.agenteval.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 回传给 Agent 的反馈粒度。粒度越细任务越容易，也越可能被当成「答案预言机」逐位试探
 * （EdgeBench 论文附录 C 的真实攻击），由任务作者按题目性质权衡。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public enum FeedbackLevel {

    /** 只回分数与是否通过。 */
    SUMMARY,
    /** 回分数 + 失败规则的对外文案（不含规则内部细节与 expected 值）。 */
    FAILED_RULES,
    /** 回除 private_notes 外的完整评分结果。 */
    FULL;

    /**
     * 序列化为小写形式。
     *
     * @return 小写枚举名
     */
    @JsonValue
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
