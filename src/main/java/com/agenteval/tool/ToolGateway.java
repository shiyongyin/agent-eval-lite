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
 * 工具网关：Agent 调用外部能力的唯一合法通道（allowlist + mock/真实后端 + 全量留痕）。
 *
 * <p>默认（replay 模式）应答取自 {@code hidden/tools/<name>.responses.yaml}：
 * <pre>{@code
 * responses:
 *   - match: {user_id: "u_1001"}   # 子集匹配：match 的每个字段与输入相应字段深度相等
 *     response: {...}
 * default_response: {...}          # 可选兜底
 * }</pre>
 * 首个命中的 match 生效；均未命中且无兜底时返回 {@code no_fixture} 失败。
 * 应答库保证评估确定性、可复现、零外部依赖。
 *
 * <p>Phase 3 真实工具：任务在 {@code allowed_tools[].backend} 声明 http 后端后，
 * 运行方以 {@code AEL_TOOL_MODE=live} 开启真实外呼（{@link ToolHttpBackend}，
 * 响应自动存档可晋升为应答库）；默认 replay 模式下后端声明被忽略、仍走应答库，
 * CI 永远确定性。模式语义见 {@link ToolMode}。
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
    private final ToolMode mode;
    private final ToolHttpBackend httpBackend;

    private ToolGateway(Path runDir, Path taskDir, TaskSpec spec, TraceLogger trace) {
        this.runDir = runDir;
        this.taskDir = taskDir;
        this.spec = spec;
        this.trace = trace;
        this.mode = ToolMode.current();
        this.httpBackend = new ToolHttpBackend(runDir.resolve("tools"));
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
     * 执行一次工具调用（allowlist 检查 → 按模式取应答库或真实外呼 → 留痕）。
     *
     * @param toolName 工具名
     * @param input 调用入参
     * @return 调用结果（含 call_id 凭证）
     */
    public ToolCallResult call(String toolName, JsonNode input) {
        String callId = newCallId();
        TaskSpec.AllowedTool tool = spec.allowedTools().stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .orElse(null);
        if (tool == null) {
            logCall(callId, toolName, input, false, true, null, "tool_not_allowed");
            return new ToolCallResult(callId, false, null,
                    "工具未在本任务的 allowlist 中: " + toolName);
        }

        // live 模式 + 后端声明 = 真实外呼（响应自动存档）；其余一律走确定性应答库。
        if (mode == ToolMode.LIVE && tool.backend() != null) {
            ToolHttpBackend.Result result = httpBackend.call(toolName, tool.backend(), input);
            if (result.error() != null) {
                logCall(callId, toolName, input, false, false, null, result.error());
                return new ToolCallResult(callId, false, null,
                        "真实后端调用失败: " + result.error());
            }
            logCall(callId, toolName, input, true, false, result.response(), null);
            return new ToolCallResult(callId, true, result.response(), null);
        }

        JsonNode response = matchFixture(toolName, input);
        if (response == null) {
            String error = tool.backend() != null && !hasFixture(toolName)
                    ? "no_replay_archive" : "no_fixture";
            logCall(callId, toolName, input, false, true, null, error);
            return new ToolCallResult(callId, false, null,
                    "no_replay_archive".equals(error)
                            ? "后端工具尚无 replay 应答库（先用 AEL_TOOL_MODE=live 录制并晋升存档）: " + toolName
                            : "mock 应答库中没有匹配该输入的应答: " + toolName);
        }
        logCall(callId, toolName, input, true, true, response, null);
        return new ToolCallResult(callId, true, response, null);
    }

    private boolean hasFixture(String toolName) {
        return Files.isRegularFile(taskDir.resolve("hidden/tools/" + toolName + ".responses.yaml"));
    }

    private JsonNode matchFixture(String toolName, JsonNode input) {
        Path fixtureFile = taskDir.resolve("hidden/tools/" + toolName + ".responses.yaml");
        if (!Files.isRegularFile(fixtureFile)) {
            // 只有声明了后端的工具才可能走到这里（加载器豁免了它的应答库必填），按未命中处理。
            return null;
        }
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
                         boolean success, boolean mock, JsonNode output, String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("call_id", callId);
        payload.put("tool_name", toolName);
        payload.put("input", input);
        payload.put("success", success);
        payload.put("mock", mock);
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
