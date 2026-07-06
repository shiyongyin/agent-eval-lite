package com.agenteval.submission;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 提交校验结论。
 *
 * @param valid 是否通过信封 + 分型双重 schema 校验与上下文绑定校验
 * @param errors 校验错误清单（valid=true 时为空）
 * @param submission 解析后的提交内容（JSON 解析失败时为 {@code null}）
 * @param submissionType 提交声明的类型（解析失败时为 {@code null}）
 * @author shiyongyin
 * @since 0.1.0
 */
public record SubmissionValidationResult(
        boolean valid,
        List<String> errors,
        JsonNode submission,
        String submissionType) {

    public SubmissionValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * 构造通过结论。
     *
     * @param submission 提交内容
     * @param submissionType 提交类型
     * @return 通过结论
     */
    public static SubmissionValidationResult ok(JsonNode submission, String submissionType) {
        return new SubmissionValidationResult(true, List.of(), submission, submissionType);
    }

    /**
     * 构造失败结论。
     *
     * @param errors 错误清单
     * @param submission 已解析的提交（可为 {@code null}）
     * @return 失败结论
     */
    public static SubmissionValidationResult fail(List<String> errors, JsonNode submission) {
        return new SubmissionValidationResult(false, errors, submission, null);
    }
}
