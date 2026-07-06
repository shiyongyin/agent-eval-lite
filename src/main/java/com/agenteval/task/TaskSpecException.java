package com.agenteval.task;

import java.util.List;

/**
 * 任务规格非法异常：聚合全部校验错误一次性抛出（fail-fast，配错的任务根本不允许起跑）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public class TaskSpecException extends RuntimeException {

    private final List<String> errors;

    /**
     * 以错误清单构造异常。
     *
     * @param taskRef 任务标识或路径（用于错误信息定位）
     * @param errors 全部校验错误
     */
    public TaskSpecException(String taskRef, List<String> errors) {
        super("任务规格非法 [" + taskRef + "]:\n  - " + String.join("\n  - ", errors));
        this.errors = List.copyOf(errors);
    }

    /**
     * 返回全部校验错误。
     *
     * @return 错误消息列表（不可变）
     */
    public List<String> errors() {
        return errors;
    }
}
