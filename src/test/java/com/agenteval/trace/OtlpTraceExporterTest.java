package com.agenteval.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.agent.ScriptedAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OTLP/OpenInference 导出适配器的端到端回归：用真实 run 的 trace 驱动，
 * 验证 span 结构（AGENT/CHAIN/TOOL 三层）、父子区间合法性、确定性（幂等导出）
 * 与 OTLP/HTTP 推送（本地 HTTP 收集器实收实测）。
 */
class OtlpTraceExporterTest {

    @TempDir
    static Path runsRoot;

    private static Path runDir;

    @BeforeAll
    static void runOnce() {
        Path taskDir = Path.of("tasks", "tool-call-001");
        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, "otlp-test",
                new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));
        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        runDir = outcome.runDir();
    }

    @Test
    void 导出结构_三层span齐全且父子引用一致() {
        ObjectNode request = OtlpTraceExporter.build(runDir);

        List<JsonNode> spans = new ArrayList<>();
        request.path("resourceSpans").get(0).path("scopeSpans").get(0)
                .path("spans").forEach(spans::add);

        Map<String, List<JsonNode>> byKind = new HashMap<>();
        for (JsonNode span : spans) {
            byKind.computeIfAbsent(attr(span, "openinference.span.kind"), k -> new ArrayList<>())
                    .add(span);
        }
        // 1 个 run 根 span + 3 轮 attempt + 4 次工具调用（2×lookup + 2×create）。
        assertThat(byKind.get("AGENT")).hasSize(1);
        assertThat(byKind.get("CHAIN")).hasSize(3);
        assertThat(byKind.get("TOOL")).hasSize(4);

        // 父子引用自洽：TOOL 挂在 CHAIN 下，CHAIN 挂在 AGENT 根下。
        Set<String> spanIds = new HashSet<>();
        spans.forEach(s -> spanIds.add(s.path("spanId").asText()));
        String rootId = byKind.get("AGENT").get(0).path("spanId").asText();
        for (JsonNode chain : byKind.get("CHAIN")) {
            assertThat(chain.path("parentSpanId").asText()).isEqualTo(rootId);
        }
        Set<String> chainIds = new HashSet<>();
        byKind.get("CHAIN").forEach(c -> chainIds.add(c.path("spanId").asText()));
        for (JsonNode tool : byKind.get("TOOL")) {
            assertThat(chainIds).contains(tool.path("parentSpanId").asText());
            assertThat(attr(tool, "tool.name")).isIn("user.lookup", "card.create");
            assertThat(attr(tool, "input.value")).contains("u_1001");
        }
        // 同一 trace、span id 全局唯一、区间合法（end >= start）。
        assertThat(spanIds).hasSize(spans.size());
        for (JsonNode span : spans) {
            assertThat(span.path("traceId").asText())
                    .isEqualTo(spans.get(0).path("traceId").asText())
                    .hasSize(32);
            assertThat(span.path("spanId").asText()).hasSize(16);
            assertThat(Long.parseLong(span.path("endTimeUnixNano").asText()))
                    .isGreaterThanOrEqualTo(Long.parseLong(span.path("startTimeUnixNano").asText()));
        }

        // 判分结果已冒泡到 attempt span 属性（外接看板可直接按 score/passed 过滤）。
        List<String> judged = byKind.get("CHAIN").stream().map(c -> attr(c, "judge.passed")).toList();
        assertThat(judged).containsExactly("false", "false", "true");
    }

    @Test
    void 导出确定性_同一run重复导出字节级一致() throws Exception {
        Path out1 = runsRoot.resolve("export-1.json");
        Path out2 = runsRoot.resolve("export-2.json");
        OtlpTraceExporter.exportToFile(runDir, out1);
        OtlpTraceExporter.exportToFile(runDir, out2);
        assertThat(Files.readString(out1, StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(out2, StandardCharsets.UTF_8));
    }

    @Test
    void 推送到OTLP端点_本地收集器完整收到请求体() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ObjectNode request = OtlpTraceExporter.build(runDir);
            int status = OtlpTraceExporter.post(request,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces");
            assertThat(status).isEqualTo(200);
            assertThat(contentType.get()).isEqualTo("application/json");
            assertThat(received.get()).contains("resourceSpans")
                    .contains("openinference.span.kind").contains("tool-call-001");
        } finally {
            server.stop(0);
        }
    }

    private static String attr(JsonNode span, String key) {
        for (JsonNode attr : span.path("attributes")) {
            if (key.equals(attr.path("key").asText())) {
                JsonNode value = attr.path("value");
                if (value.has("stringValue")) {
                    return value.path("stringValue").asText();
                }
                if (value.has("boolValue")) {
                    return String.valueOf(value.path("boolValue").asBoolean());
                }
                if (value.has("doubleValue")) {
                    return String.valueOf(value.path("doubleValue").asDouble());
                }
                return value.path("intValue").asText();
            }
        }
        return null;
    }
}
