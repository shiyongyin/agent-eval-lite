package com.agenteval.cli;

import com.agenteval.tool.ToolCallResult;
import com.agenteval.tool.ToolGateway;
import com.agenteval.tool.ToolGatewayClient;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code agent-eval tool call}：Agent 调用工具的唯一合法通道（allowlist + mock + 留痕）。
 *
 * <p>Agent 侧用法（{@code AEL_RUN_DIR} 已由框架注入）：
 * <pre>{@code
 * agent-eval tool call user.lookup --input '{"user_id":"u_1001"}'
 * agent-eval tool call user.lookup --input @query.json
 * }</pre>
 * stdout 返回 {@code {"call_id","success","output"|"error"}}；
 * {@code call_id} 必须原样写进提交的 {@code tool_calls_used}。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "tool", mixinStandardHelpOptions = true, description = "工具网关",
        subcommands = {ToolCommand.CallCommand.class})
public final class ToolCommand {

    /**
     * {@code tool call} 子命令。
     */
    @Command(name = "call", mixinStandardHelpOptions = true, description = "经网关调用一个工具")
    public static final class CallCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "工具名（须在任务 allowed_tools 中）")
        private String toolName;

        @Option(names = "--input", required = true, description = "调用入参 JSON（或 @文件路径）")
        private String input;

        @Option(names = "--run-dir", description = "run 目录（默认取环境变量 AEL_RUN_DIR）")
        private Path runDir;

        @Override
        public Integer call() throws Exception {
            String inputJson = input.startsWith("@")
                    ? Files.readString(Path.of(input.substring(1)), StandardCharsets.UTF_8)
                    : input;
            JsonNode inputNode;
            try {
                inputNode = Jsons.json().readTree(inputJson);
            } catch (Exception e) {
                System.err.println("错误: --input 不是合法 JSON: " + e.getMessage());
                return 1;
            }

            // 优先经常驻网关服务（真实 run 中始终注入端点），由服务端代写签名 trace 事件；
            // 仅在 run 之外（离线手动试调）才回退到进程内不签名网关。
            String endpoint = System.getenv("AEL_TOOL_ENDPOINT");
            if (endpoint != null && !endpoint.isBlank()) {
                try {
                    JsonNode response = ToolGatewayClient.call(
                            endpoint, System.getenv("AEL_TOOL_TOKEN"), toolName, inputNode);
                    System.out.println(Jsons.jsonCompact().writeValueAsString(response));
                    return 0;
                } catch (Exception e) {
                    System.err.println("错误: 连接工具网关服务失败: " + e.getMessage());
                    return 1;
                }
            }

            Path effectiveRunDir = runDir;
            if (effectiveRunDir == null) {
                String env = System.getenv("AEL_RUN_DIR");
                if (env == null || env.isBlank()) {
                    System.err.println("错误: 未提供 --run-dir 且环境变量 AEL_RUN_DIR 未设置");
                    return 1;
                }
                effectiveRunDir = Path.of(env);
            }
            ToolCallResult result = ToolGateway.forRun(effectiveRunDir).call(toolName, inputNode);
            ObjectNode output = Jsons.json().createObjectNode();
            output.put("call_id", result.callId());
            output.put("success", result.success());
            if (result.success()) {
                output.set("output", result.output());
            } else {
                output.put("error", result.error());
            }
            System.out.println(Jsons.jsonCompact().writeValueAsString(output));
            return 0;
        }
    }
}
