package com.agenteval.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 最佳 attempt 选择策略（借鉴 SForge 的 selection 概念，简化为三种）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public enum SelectionPolicy {

    /** 全部有效 attempt 中取最高分（同分取更早的）。 */
    BEST_SCORE,
    /** 第一个通过的 attempt（衡量「首次达标效率」）。 */
    FIRST_PASS,
    /** 最后一个有效 attempt（衡量「最终收敛状态」）。 */
    LAST;

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
