package com.agenteval.trace;

import com.agenteval.Version;
import com.agenteval.state.RunMeta;
import com.agenteval.state.RunState;
import com.agenteval.state.RunStateStore;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTLP/OpenInference 导出适配器：把 run 目录的 {@code trace.jsonl} 纯读地转换为
 * OTLP/JSON（ExportTraceServiceRequest），供 Arize Phoenix / Langfuse / 任意 OTel
 * Collector 直接摄取——自己不建看板，可视化「白嫖」外部生态（调研报告行动 7，PoC D 已验证）。
 *
 * <p>Span 映射（OpenInference 语义约定，{@code openinference.span.kind}）：
 * <pre>
 * run                      → AGENT 根 span（run_started..run_completed）
 * attempt                  → CHAIN 子 span（agent_started..agent_finished，附判分结果）
 * tool_call                → TOOL 叶子 span（挂在所属 attempt 下，含 input/output）
 * 其余事件（提交/判分/反馈…）→ 所属 span 的 span event
 * </pre>
 *
 * <p>trace/span id 由 {@code run_id}/{@code attempt_id}/{@code call_id} 的 SHA-256 前缀
 * 确定性派生：同一 run 重复导出得到字节级相同的输出（幂等，可安全重放到看板）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class OtlpTraceExporter {

    /** OTLP span 状态码：未设置。 */
    private static final int STATUS_UNSET = 0;
    /** OTLP span 状态码：错误。 */
    private static final int STATUS_ERROR = 2;

    private OtlpTraceExporter() {
    }

    /**
     * 从 run 目录构建 OTLP/JSON 请求体。
     *
     * @param runDir run 目录（须含 meta.json 与 traces/trace.jsonl）
     * @return ExportTraceServiceRequest 形态的 JSON 根节点
     */
    public static ObjectNode build(Path runDir) {
        RunMeta meta = RunMeta.load(runDir.resolve("meta.json"));
        RunState state = RunStateStore.load(runDir.resolve("run_state.json"));
        List<JsonNode> events = TraceLogger.readAll(runDir.resolve("traces/trace.jsonl"));
        if (events.isEmpty()) {
            throw new IllegalStateException("trace.jsonl 为空或不存在，无可导出内容: " + runDir);
        }

        String traceId = deriveId(meta.runId(), 16);
        String rootSpanId = deriveId(meta.runId() + "#run", 8);

        // 先按 attempt 分桶：每个 attempt 一个 CHAIN span，工具调用与事件挂到所属桶。
        Map<String, ObjectNode> attemptSpans = new LinkedHashMap<>();
        ArrayNode spans = Jsons.json().createArrayNode();

        ObjectNode rootSpan = newSpan(traceId, rootSpanId, null,
                "run " + meta.taskId(),
                timestampOf(events.get(0)), timestampOf(events.get(events.size() - 1)));
        addAttr(attrsOf(rootSpan), "openinference.span.kind", "AGENT");
        addAttr(attrsOf(rootSpan), "task.id", meta.taskId());
        addAttr(attrsOf(rootSpan), "agent.name", meta.agentName());
        addAttr(attrsOf(rootSpan), "model.name", meta.modelName());
        addAttr(attrsOf(rootSpan), "run.id", meta.runId());
        addAttr(attrsOf(rootSpan), "engine.version", meta.engineVersion());
        if (state != null) {
            addAttr(attrsOf(rootSpan), "run.status", state.status().name());
            boolean broken = "INTEGRITY_BROKEN".equals(state.status().name())
                    || "ERROR".equals(state.status().name());
            rootSpan.putObject("status").put("code", broken ? STATUS_ERROR : STATUS_UNSET);
        }
        spans.add(rootSpan);

        for (JsonNode event : events) {
            String type = event.path("type").asText();
            String attemptId = event.path("attempt_id").asText(null);
            switch (type) {
                case "agent_started" -> {
                    ObjectNode span = newSpan(traceId,
                            deriveId(meta.runId() + "#" + attemptId, 8), rootSpanId,
                            "attempt " + attemptId, timestampOf(event), timestampOf(event));
                    addAttr(attrsOf(span), "openinference.span.kind", "CHAIN");
                    addAttr(attrsOf(span), "attempt.id", attemptId);
                    attemptSpans.put(attemptId, span);
                    spans.add(span);
                }
                case "agent_finished" -> {
                    ObjectNode span = attemptSpans.get(attemptId);
                    if (span != null) {
                        span.put("endTimeUnixNano", timestampOf(event));
                        addAttr(attrsOf(span), "agent.exit_code",
                                event.path("payload").path("exit_code").asLong(0));
                    }
                }
                case "tool_call" -> {
                    JsonNode payload = event.path("payload");
                    ObjectNode owner = attemptSpans.get(attemptId);
                    ObjectNode span = newSpan(traceId,
                            deriveId(meta.runId() + "#tool#" + payload.path("call_id").asText(), 8),
                            owner != null ? owner.path("spanId").asText() : rootSpanId,
                            "tool " + payload.path("tool_name").asText(),
                            timestampOf(event), timestampOf(event));
                    ArrayNode attrs = attrsOf(span);
                    addAttr(attrs, "openinference.span.kind", "TOOL");
                    addAttr(attrs, "tool.name", payload.path("tool_name").asText());
                    addAttr(attrs, "tool.call_id", payload.path("call_id").asText());
                    addAttr(attrs, "input.value", payload.path("input").toString());
                    if (payload.hasNonNull("output_summary")) {
                        addAttr(attrs, "output.value", payload.path("output_summary").asText());
                    }
                    addAttrBool(attrs, "tool.success", payload.path("success").asBoolean(false));
                    if (payload.hasNonNull("error")) {
                        addAttr(attrs, "tool.error", payload.path("error").asText());
                    }
                    span.putObject("status").put("code",
                            payload.path("success").asBoolean(false) ? STATUS_UNSET : STATUS_ERROR);
                    // 延长所属 attempt span 的结束时间，保证父子区间嵌套合法。
                    if (owner != null) {
                        extendEnd(owner, timestampOf(event));
                    }
                    spans.add(span);
                }
                case "judge_completed" -> {
                    ObjectNode span = attemptSpans.get(attemptId);
                    if (span != null) {
                        ArrayNode attrs = attrsOf(span);
                        addAttrDouble(attrs, "judge.score", event.path("payload").path("score").asDouble());
                        addAttrBool(attrs, "judge.passed", event.path("payload").path("passed").asBoolean(false));
                        extendEnd(span, timestampOf(event));
                    }
                    appendEvent(attemptSpans.get(attemptId), rootSpan, event);
                }
                default -> appendEvent(attemptSpans.get(attemptId), rootSpan, event);
            }
        }

        // 收尾：没有 agent_finished 的 attempt（异常中断）以最后一个相关事件为界。
        ObjectNode root = Jsons.json().createObjectNode();
        ArrayNode resourceSpans = root.putArray("resourceSpans");
        ObjectNode resourceSpan = resourceSpans.addObject();
        ArrayNode resourceAttrs = resourceSpan.putObject("resource").putArray("attributes");
        addAttr(resourceAttrs, "service.name", "agent-eval-lite");
        addAttr(resourceAttrs, "task.id", meta.taskId());
        ObjectNode scopeSpan = resourceSpan.putArray("scopeSpans").addObject();
        scopeSpan.putObject("scope")
                .put("name", "com.agenteval.trace")
                .put("version", Version.ENGINE);
        scopeSpan.set("spans", spans);
        return root;
    }

    /**
     * 导出到文件。
     *
     * @param runDir run 目录
     * @param outFile 输出文件（父目录自动创建）
     * @return 写出的文件路径
     */
    public static Path exportToFile(Path runDir, Path outFile) {
        ObjectNode request = build(runDir);
        try {
            if (outFile.toAbsolutePath().getParent() != null) {
                Files.createDirectories(outFile.toAbsolutePath().getParent());
            }
            Files.writeString(outFile, request.toPrettyString(), StandardCharsets.UTF_8);
            return outFile;
        } catch (IOException e) {
            throw new UncheckedIOException("写出 OTLP 导出文件失败: " + outFile, e);
        }
    }

    /**
     * 把请求体 POST 到 OTLP/HTTP 端点（如 Phoenix 的 {@code /v1/traces}）。
     *
     * @param request OTLP/JSON 请求体
     * @param endpoint 完整端点 URL
     * @return HTTP 状态码
     * @throws IOException 网络失败时
     * @throws InterruptedException 等待被中断时
     */
    public static int post(ObjectNode request, String endpoint) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Jsons.jsonCompact().writeValueAsString(request), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /**
     * 统计请求体中的 span 数（CLI 输出用）。
     *
     * @param request OTLP/JSON 请求体
     * @return span 总数
     */
    public static int spanCount(JsonNode request) {
        int count = 0;
        for (JsonNode resourceSpan : request.path("resourceSpans")) {
            for (JsonNode scopeSpan : resourceSpan.path("scopeSpans")) {
                count += scopeSpan.path("spans").size();
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- helper

    private static ArrayNode attrsOf(ObjectNode span) {
        return (ArrayNode) span.get("attributes");
    }

    private static ArrayNode eventsOf(ObjectNode span) {
        return (ArrayNode) span.get("events");
    }

    private static ObjectNode newSpan(String traceId, String spanId, String parentSpanId,
                                      String name, String startNano, String endNano) {
        ObjectNode span = Jsons.json().createObjectNode();
        span.put("traceId", traceId);
        span.put("spanId", spanId);
        if (parentSpanId != null) {
            span.put("parentSpanId", parentSpanId);
        }
        span.put("name", name);
        // OTLP SPAN_KIND_INTERNAL；语义角色由 openinference.span.kind 属性表达。
        span.put("kind", 1);
        span.put("startTimeUnixNano", startNano);
        span.put("endTimeUnixNano", endNano);
        span.putArray("attributes");
        span.putArray("events");
        return span;
    }

    private static void appendEvent(ObjectNode attemptSpan, ObjectNode rootSpan, JsonNode event) {
        ObjectNode owner = attemptSpan != null ? attemptSpan : rootSpan;
        ObjectNode spanEvent = eventsOf(owner).addObject();
        spanEvent.put("timeUnixNano", timestampOf(event));
        spanEvent.put("name", event.path("type").asText());
        ArrayNode attrs = spanEvent.putArray("attributes");
        // 只带标量负载字段：span event 是概览锚点，细节仍以 trace.jsonl 为准。
        event.path("payload").properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual()) {
                addAttr(attrs, entry.getKey(), value.asText());
            } else if (value.isBoolean()) {
                addAttrBool(attrs, entry.getKey(), value.asBoolean());
            } else if (value.isNumber()) {
                addAttrDouble(attrs, entry.getKey(), value.asDouble());
            }
        });
        if (attemptSpan != null) {
            extendEnd(attemptSpan, timestampOf(event));
        }
    }

    private static void extendEnd(ObjectNode span, String candidateNano) {
        String current = span.path("endTimeUnixNano").asText("0");
        if (Long.parseLong(candidateNano) > Long.parseLong(current)) {
            span.put("endTimeUnixNano", candidateNano);
        }
    }

    private static String timestampOf(JsonNode event) {
        Instant instant = Instant.parse(event.path("timestamp").asText());
        long nanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        return Long.toString(nanos);
    }

    private static void addAttr(ArrayNode attrs, String key, String value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        attr.putObject("value").put("stringValue", value == null ? "" : value);
    }

    private static void addAttr(ArrayNode attrs, String key, long value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        // proto3 JSON 映射要求 int64 编码为字符串。
        attr.putObject("value").put("intValue", Long.toString(value));
    }

    private static void addAttrBool(ArrayNode attrs, String key, boolean value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        attr.putObject("value").put("boolValue", value);
    }

    private static void addAttrDouble(ArrayNode attrs, String key, double value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        attr.putObject("value").put("doubleValue", value);
    }

    private static String deriveId(String seed, int bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[bytes];
            System.arraycopy(digest, 0, truncated, 0, bytes);
            return HexFormat.of().formatHex(truncated);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
