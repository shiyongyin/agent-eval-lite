package com.agenteval.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次网关工具调用的结果。{@code callId} 是调用凭证：提交中引用它，
 * 评审据 trace 核验「真调过还是编的」。
 *
 * @param callId 调用凭证（trace 中可查）
 * @param success 是否成功
 * @param output 工具输出（失败时为 {@code null}）
 * @param error 失败原因（成功时为 {@code null}）
 * @author shiyongyin
 * @since 0.1.0
 */
public record ToolCallResult(String callId, boolean success, JsonNode output, String error) {
}
