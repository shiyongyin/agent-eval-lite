package com.agenteval.task;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 任务类型：决定提交分型 schema 的默认选择（{@code builtin:<type>}），并进入报告用于横向归类。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public enum TaskType {

    /** 通用任务：answer 为自由结构对象。 */
    GENERIC,
    /** 代码修改任务：必须申报 changed_files 与 tests_run。 */
    CODE_FIX,
    /** API payload 生成任务：必须产出 final_payload。 */
    API_PAYLOAD,
    /** 文档 / 分析任务：必须产出 deliverable 并给出 sources。 */
    DOCUMENT,
    /** 工具调用任务：必须真实调用框架工具并引用 call_id。 */
    TOOL_CALL,
    /** 需求 / 方案评审任务：deliverable + 风险点覆盖。 */
    REVIEW,
    /** 自定义任务：提交 schema 必须由任务自带文件提供。 */
    CUSTOM;

    /**
     * 序列化为小写形式（task.yaml / report 中的书写形态）。
     *
     * @return 小写枚举名
     */
    @JsonValue
    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
