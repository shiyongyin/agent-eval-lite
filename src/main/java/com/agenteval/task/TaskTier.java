package com.agenteval.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 任务分层：给任务库中的任务标注「批跑意图」，供 {@code suite --tier} 做子集过滤。
 *
 * <p>分层是<strong>运行编排</strong>层面的元数据，不影响单任务的判分口径——同一任务无论属于
 * 哪一层，规则、通过线、可复现指纹都完全一致。典型用法：CI 主干只跑 {@link #SMOKE} 快速回归，
 * 夜间全量跑 {@link #REGRESSION}，安全门禁单独跑 {@link #SECURITY}。
 *
 * @author shiyongyin
 * @since 0.5.0
 */
public enum TaskTier {

    /** 冒烟层：最小、最快、最有代表性的核心任务，供每次提交的快速门禁。 */
    SMOKE,
    /** 回归层：覆盖面更广的常规任务集（默认层），供全量回归。 */
    REGRESSION,
    /** 安全层：以对抗 / 隔离 / 越权为评估重点的任务，供安全门禁单独批跑。 */
    SECURITY,
    /** 领域层：贴合特定业务领域、需要领域知识的任务，供领域专项评估。 */
    DOMAIN;

    /**
     * 序列化为小写形式（task.yaml / 报告 / CLI 参数中的书写形态）。
     *
     * @return 小写枚举名
     */
    @JsonValue
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
