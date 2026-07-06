package com.agenteval.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Judge 执行类型。确定性优先：能用 {@code rules} 解决的不用 {@code script}，
 * 能用 {@code script} 解决的不用 LLM（LLM judge 为后续阶段，Phase 1 未实现）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public enum JudgeType {

    /** 纯规则引擎：hidden/judge.rules.yaml 里的确定性 checks。 */
    RULES,
    /** 外部脚本：任务自带评分程序（python/bash 等），按 stdout JSON 契约返回 checks。 */
    SCRIPT,
    /** 规则 + 脚本合并计分。 */
    HYBRID;

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
