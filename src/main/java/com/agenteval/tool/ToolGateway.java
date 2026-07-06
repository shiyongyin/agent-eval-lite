package com.agenteval.tool;

import com.agenteval.state.RunMeta;
import com.agenteval.state.RunState;
import com.agenteval.state.RunStateStore;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import com.agenteval.trace.TraceEventType;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具网关：Agent 调用外部能力的唯一合法通道（allowlist + mock 应答 + 全量留痕）。
 *
 * <p>Phase 1 的工具一律 mock——应答库在 {@code hidden/tools/<name>.responses.yaml}：
 * <pre>{@code
 * responses:
 *   - match: {user_id: "u_1001"}   # 子集匹配：match 的每个字段与输入相应字段深度相等
 *     response: {...}
 * default_response: {...}          # 可选兜底
 * }</pre>
 * 首个命中的 match 生效；均未命中且无兜底时返回 {@code no_fixture} 失败。
 * mock 保证评估确定性、可复现、零外部依赖；真实 HTTP/MCP 工具是 Phase 3 的扩展点。
 *
 * <p>网关通常运行在独立进程（Agent 执行 {@code agent-eval tool call}），
 * 凭 {@code AEL_RUN_DIR} → meta.json 还原任务上下文，trace 以 append 方式续写。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class ToolGateway {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Path runDir;
    private final Path taskDir;
    private final TaskSpec spec;
    private final TraceLogger trace;

    private ToolGateway(Path runDir, Path taskDir, TaskSpec spec, TraceLogger trace) {
        this.runDir = runDir;
        this.taskDir = taskDir;
        this.spec = spec;
        this.trace = trace;
    }

    /**
     * 从 run 目录还原网关（自开<strong>不签名</strong>的 trace）。
     *
     * <p>仅用于「run 之外」的场景：离线/人工手动试调工具。在真实 run 中，网关由
     * {@link #withSigningTrace} 绑定框架进程内的签名 trace，经
     * {@link ToolGatewayServer} 常驻对外提供，Agent 子进程无法据此伪造签名事件。
     *
     * @param runDir run 目录（含 meta.json）
     * @return 网关实例
     */
    public static ToolGateway forRun(Path runDir) {
        RunMeta meta = RunMeta.load(runDir.resolve("meta.json"));
        Path taskDir = Path.of(meta.taskDir());
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        TraceLogger trace = TraceLogger.open(runDir.resolve("traces/trace.jsonl"), meta.runId());
        return new ToolGateway(runDir, taskDir, spec, trace);
    }

    /**
     * 用框架进程内的签名 trace 构建网关——这是真实 run 里唯一会产出「可核验 tool_call 事件」的路径。
     *
     * @param runDir run 目录
     * @param taskDir 任务目录
     * @param spec 任务规格
     * @param signingTrace 携带每 run 密钥的签名 trace
     * @return 网关实例
     */
    public static ToolGateway withSigningTrace(Path runDir, Path taskDir, TaskSpec spec,
                                               TraceLogger signingTrace) {
        return new ToolGateway(runDir, taskDir, spec, signingTrace);
    }

    /**
     * 执行一次工具调用（allowlist 检查 → mock 匹配 → 留痕）。
     *
     * @param toolName 工具名
     * @param input 调用入参
     * @return 调用结果（含 call_id 凭证）
     */
    public ToolCallResult call(String toolName, JsonNode input) {
        String callId = newCallId();
        boolean allowed = spec.allowedTools().stream().anyMatch(t -> t.name().equals(toolName));
        if (!allowed) {
            logCall(callId, toolName, input, false, null, "tool_not_allowed");
            return new ToolCallResult(callId, false, null,
                    "工具未在本任务的 allowlist 中: " + toolName);
        }
        JsonNode response = matchFixture(toolName, input);
        if (response == null) {
            logCall(callId, toolName, input, false, null, "no_fixture");
            return new ToolCallResult(callId, false, null,
                    "mock 应答库中没有匹配该输入的应答: " + toolName);
        }
        logCall(callId, toolName, input, true, response, null);
        return new ToolCallResult(callId, true, response, null);
    }

    private JsonNode matchFixture(String toolName, JsonNode input) {
        Path fixtureFile = taskDir.resolve("hidden/tools/" + toolName + ".responses.yaml");
        JsonNode root;
        try {
            root = Jsons.yaml().readTree(Files.readString(fixtureFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取工具应答库失败: " + fixtureFile, e);
        }
        for (JsonNode candidate : root.path("responses")) {
            JsonNode match = candidate.path("match");
            boolean hit = true;
            var fields = match.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().equals(input.path(field.getKey()))) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return candidate.path("response");
            }
        }
        JsonNode fallback = root.path("default_response");
        return fallback.isMissingNode() ? null : fallback;
    }

    private void logCall(String callId, String toolName, JsonNode input,
                         boolean success, JsonNode output, String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("call_id", callId);
        payload.put("tool_name", toolName);
        payload.put("input", input);
        payload.put("success", success);
        payload.put("mock", true);
        if (output != null) {
            String summary = output.toString();
            payload.put("output_summary", summary.length() > 2048 ? summary.substring(0, 2048) + "…" : summary);
        }
        if (errorCode != null) {
            payload.put("error", errorCode);
        }
        trace.log(TraceEventType.TOOL_CALL, currentAttemptId(), payload);
    }

    private String currentAttemptId() {
        RunState state = RunStateStore.load(runDir.resolve("run_state.json"));
        if (state == null) {
            return null;
        }
        return String.format("attempt_%03d", state.nextAttemptNumber());
    }

    private static String newCallId() {
        StringBuilder sb = new StringBuilder("tc_");
        for (int i = 0; i < 8; i++) {
            sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
