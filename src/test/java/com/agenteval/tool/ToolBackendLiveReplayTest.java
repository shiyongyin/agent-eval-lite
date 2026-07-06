package com.agenteval.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真实工具后端（Phase 3）端到端回归：起<strong>真实本地 HTTP 服务</strong>扮演工具后端，
 * 验证「live 真实外呼 → 响应存档 → 存档晋升为应答库 → 默认 replay 确定性回放」的完整闭环，
 * 以及白名单/凭证/失败路径的 fail-closed 语义。
 */
class ToolBackendLiveReplayTest {

    @TempDir
    Path tempRoot;

    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("ael.tool.mode");
    }

    @Test
    void live外呼并存档_晋升应答库后replay不再外呼且结果一致() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        List<String> apiKeys = new CopyOnWriteArrayList<>();
        String url = startBackend("/weather", exchange -> {
            hits.incrementAndGet();
            apiKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Api-Key")));
            JsonNode input = Jsons.json().readTree(exchange.getRequestBody());
            byte[] body = ("{\"city\":\"" + input.path("city").asText() + "\",\"temp_c\":31}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        Path taskDir = backendTask("tool-live-001", url, "        X-Api-Key: static-key-01\n");
        Path runDir = Files.createDirectories(tempRoot.resolve("run1"));

        // live 模式：真实打到后端，响应返回给调用方并存档。
        System.setProperty("ael.tool.mode", "live");
        ToolCallResult live = gateway(runDir, taskDir)
                .call("weather.lookup", Jsons.json().readTree("{\"city\":\"hangzhou\"}"));
        assertThat(live.success()).isTrue();
        assertThat(live.output().path("temp_c").asInt()).isEqualTo(31);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(apiKeys).containsExactly("static-key-01");

        // 存档与应答库同格式（match=入参，response=真实响应）。
        Path recorded = runDir.resolve("tools/weather.lookup.recorded.yaml");
        assertThat(recorded).isRegularFile();
        JsonNode archive = Jsons.yaml().readTree(Files.readString(recorded, StandardCharsets.UTF_8));
        assertThat(archive.path("responses").get(0).path("match").path("city").asText()).isEqualTo("hangzhou");
        assertThat(archive.path("responses").get(0).path("response").path("temp_c").asInt()).isEqualTo(31);

        // trace 中 live 调用标记 mock=false。
        List<JsonNode> events1 = TraceLogger.readAll(runDir.resolve("traces/trace.jsonl"));
        assertThat(events1).hasSize(1);
        assertThat(events1.get(0).path("payload").path("mock").asBoolean(true)).isFalse();

        // 晋升：把录制存档原样复制为任务的 replay 应答库。
        Files.copy(recorded, taskDir.resolve("hidden/tools/weather.lookup.responses.yaml"));

        // 默认 replay 模式：同输入取回同响应，后端零新增调用（确定性、可断网）。
        System.clearProperty("ael.tool.mode");
        Path runDir2 = Files.createDirectories(tempRoot.resolve("run2"));
        ToolCallResult replayed = gateway(runDir2, taskDir)
                .call("weather.lookup", Jsons.json().readTree("{\"city\":\"hangzhou\"}"));
        assertThat(replayed.success()).isTrue();
        assertThat(replayed.output()).isEqualTo(live.output());
        assertThat(hits.get()).isEqualTo(1);
        List<JsonNode> events2 = TraceLogger.readAll(runDir2.resolve("traces/trace.jsonl"));
        assertThat(events2.get(0).path("payload").path("mock").asBoolean(false)).isTrue();
    }

    @Test
    void replay模式下后端工具无应答库_收敛为可读失败而非崩溃() throws Exception {
        Path taskDir = backendTask("tool-live-002", "http://127.0.0.1:1/never", "");
        Path runDir = Files.createDirectories(tempRoot.resolve("run"));

        ToolCallResult result = gateway(runDir, taskDir)
                .call("weather.lookup", Jsons.json().readTree("{\"city\":\"x\"}"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("replay 应答库").contains("AEL_TOOL_MODE=live");
    }

    @Test
    void live模式header占位符缺环境变量_按fail_closed拒绝调用() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = startBackend("/secure", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        Path taskDir = backendTask("tool-live-003", url,
                "        Authorization: Bearer ${ENV:AEL_TEST_MISSING_CRED_XYZ}\n");
        Path runDir = Files.createDirectories(tempRoot.resolve("run"));

        System.setProperty("ael.tool.mode", "live");
        ToolCallResult result = gateway(runDir, taskDir)
                .call("weather.lookup", Jsons.json().readTree("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("backend_header_env_missing").contains("AEL_TEST_MISSING_CRED_XYZ");
        assertThat(hits.get()).isZero();
    }

    @Test
    void live模式后端返回500或不可达_收敛为结构化失败且留痕() throws Exception {
        String url = startBackend("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        Path taskDir = backendTask("tool-live-004", url, "");
        Path runDir = Files.createDirectories(tempRoot.resolve("run"));

        System.setProperty("ael.tool.mode", "live");
        ToolGateway gateway = gateway(runDir, taskDir);
        ToolCallResult http500 = gateway.call("weather.lookup", Jsons.json().readTree("{}"));
        assertThat(http500.success()).isFalse();
        assertThat(http500.error()).contains("backend_http_500");

        server.stop(0);
        Path taskDir2 = backendTask("tool-live-005", "http://127.0.0.1:1/gone", "");
        ToolCallResult unreachable = gateway(Files.createDirectories(tempRoot.resolve("run5")), taskDir2)
                .call("weather.lookup", Jsons.json().readTree("{}"));
        assertThat(unreachable.success()).isFalse();
        assertThat(unreachable.error()).contains("backend_unreachable");

        List<JsonNode> events = TraceLogger.readAll(runDir.resolve("traces/trace.jsonl"));
        assertThat(events.get(0).path("payload").path("success").asBoolean(true)).isFalse();
        assertThat(events.get(0).path("payload").path("error").asText()).isEqualTo("backend_http_500");
    }

    @Test
    void get后端_扁平入参编码为查询参数_嵌套入参被拒绝() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        String url = startBackend("/lookup", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        Path taskDir = backendTaskWithMethod("tool-live-006", url, "GET");
        Path runDir = Files.createDirectories(tempRoot.resolve("run"));

        System.setProperty("ael.tool.mode", "live");
        ToolGateway gateway = gateway(runDir, taskDir);
        ToolCallResult flat = gateway.call("weather.lookup",
                Jsons.json().readTree("{\"city\":\"hang zhou\",\"days\":3}"));
        assertThat(flat.success()).isTrue();
        assertThat(queries).containsExactly("city=hang+zhou&days=3");

        ToolCallResult nested = gateway.call("weather.lookup",
                Jsons.json().readTree("{\"filter\":{\"a\":1}}"));
        assertThat(nested.success()).isFalse();
        assertThat(nested.error()).contains("backend_get_input_not_flat");
    }

    // ---------------------------------------------------------------- fixture

    private ToolGateway gateway(Path runDir, Path taskDir) {
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        TraceLogger trace = TraceLogger.open(runDir.resolve("traces/trace.jsonl"), "run_tool_test");
        return ToolGateway.withSigningTrace(runDir, taskDir, spec, trace);
    }

    private Path backendTask(String taskId, String url, String headerLines) throws IOException {
        return writeTask(taskId, url, "POST", headerLines);
    }

    private Path backendTaskWithMethod(String taskId, String url, String method) throws IOException {
        return writeTask(taskId, url, method, "");
    }

    private Path writeTask(String taskId, String url, String method, String headerLines) throws IOException {
        Path taskDir = tempRoot.resolve(taskId);
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("hidden/tools"));
        String headersBlock = headerLines.isBlank() ? "" : "      headers:\n" + headerLines;
        Files.writeString(taskDir.resolve("task.yaml"), """
                schema_version: 1
                task_id: %s
                task_name: 工具后端测试任务
                task_type: generic
                agent_brief: 调用天气工具
                allowed_tools:
                  - name: weather.lookup
                    description: 查询城市天气
                    backend:
                      type: http
                      url: %s
                      method: %s
                      timeout_seconds: 5
                %s
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - name: correctness
                      weight: 100
                runtime:
                  timeout_minutes: 5
                  attempt_timeout_minutes: 2
                """.formatted(taskId, url, method, headersBlock), StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("hidden/judge.rules.yaml"), """
                schema_version: 1
                judge_version: v1
                checks:
                  - id: HAS_ANSWER
                    type: jsonpath_exists
                    dimension: correctness
                    points: 10
                    path: $.answer
                """, StandardCharsets.UTF_8);
        return taskDir;
    }

    private String startBackend(String path, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
