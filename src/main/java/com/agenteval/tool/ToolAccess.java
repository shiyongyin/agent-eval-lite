package com.agenteval.tool;

/**
 * 一次 run 内「工具调用能力」的载体，由 Runner 交给各适配器。
 *
 * <p>两条使用路径共用同一个签名网关，因此不论谁调用，产出的 {@code tool_call} 事件都带合法签名：
 * <ul>
 *   <li><strong>进程内适配器</strong>（如脚本回放）直接用 {@link #gateway()} 调用；</li>
 *   <li><strong>子进程适配器</strong>（CLI Agent）把 {@link #endpoint()}/{@link #token()} 注入环境，
 *       由 {@code agent-eval tool call} 经 {@link ToolGatewayClient} 连回常驻服务。</li>
 * </ul>
 *
 * @param gateway 绑定签名 trace 的进程内网关
 * @param endpoint 常驻服务端点（{@code host:port}）
 * @param token 调用凭证
 * @author shiyongyin
 * @since 0.2.0
 */
public record ToolAccess(ToolGateway gateway, String endpoint, String token) {
}
