package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenteval.agent.HttpAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HTTP Agent 适配器端到端回归：起一个<strong>真实本地 HTTP 服务</strong>扮演服务型 Agent，
 * 验证「框架按轮 POST 任务说明 → 服务响应体即提交 → 判分 → 受控反馈 → 修正通过」全链路，
 * 以及请求契约（protocol / instructions / feedback / 自定义头）与各失败路径的语义。
 */
class HttpAgentRunTest {

    @TempDir
    Path runsRoot;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 服务型Agent_首轮失败按受控反馈修正后第二轮通过() throws Exception {
        Path taskDir = Path.of("tasks", "api-payload-001");
        String fail = Files.readString(taskDir.resolve("samples/attempt-fail.json"), StandardCharsets.UTF_8);
        String pass = Files.readString(taskDir.resolve("samples/attempt-pass.json"), StandardCharsets.UTF_8);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authHeaders = new CopyOnWriteArrayList<>();

        String endpoint = startAgentService(exchange -> {
            JsonNode request = Jsons.json().readTree(exchange.getRequestBody());
            requests.add(request);
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String template = request.path("attempt_number").asInt() == 1 ? fail : pass;
            byte[] body = template.replace("{attempt_id}", request.path("attempt_id").asText())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, "http-demo",
                new HttpAgentAdapter(endpoint, List.of("Authorization: Bearer test-token")));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_002");
        assertThat(outcome.bestScore()).isEqualTo(100.0);

        // 请求契约：首轮带完整任务说明、feedback 为 null；自定义头生效。
        JsonNode first = requests.get(0);
        assertThat(first.path("protocol").asText()).isEqualTo("ael-http-agent/1");
        assertThat(first.path("task_id").asText()).isEqualTo("api-payload-001");
        assertThat(first.path("attempt_id").asText()).isEqualTo("attempt_001");
        assertThat(first.path("max_attempts").asInt()).isEqualTo(3);
        assertThat(first.path("instructions").asText()).contains("payload");
        assertThat(first.path("feedback").isNull()).isTrue();
        assertThat(first.path("workspace_dir").asText()).isNotBlank();
        assertThat(first.path("tool_gateway").path("endpoint").asText()).contains(":");
        assertThat(authHeaders).allMatch("Bearer test-token"::equals);

        // 第二轮带上一轮受控反馈（只有失败规则文案，不泄露 expected 值 4950）。
        JsonNode second = requests.get(1);
        assertThat(second.path("attempt_number").asInt()).isEqualTo(2);
        assertThat(second.path("feedback").isObject()).isTrue();
        assertThat(second.path("feedback").toString()).doesNotContain("4950");

        // meta 记录 http 适配器。
        JsonNode meta = Jsons.json().readTree(Files.readString(outcome.runDir().resolve("meta.json")));
        assertThat(meta.path("agent_name").asText()).isEqualTo("http");
    }

    @Test
    void 服务返回204_按Agent放弃收束为agent_exhausted() throws Exception {
        Path taskDir = Path.of("tasks", "api-payload-001");
        String endpoint = startAgentService(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                new HttpAgentAdapter(endpoint, null));

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
        assertThat(report.path("run").path("status_reason").asText()).isEqualTo("agent_exhausted");
    }

    @Test
    void 服务首轮500_按无提交轮反馈提醒_次轮恢复后仍可通过() throws Exception {
        Path taskDir = Path.of("tasks", "api-payload-001");
        String pass = Files.readString(taskDir.resolve("samples/attempt-pass.json"), StandardCharsets.UTF_8);
        String endpoint = startAgentService(exchange -> {
            JsonNode request = Jsons.json().readTree(exchange.getRequestBody());
            if (request.path("attempt_number").asInt() == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] body = pass.replace("{attempt_id}", request.path("attempt_id").asText())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        RunManager.RunOutcome outcome = RunManager.run(taskDir, runsRoot, null,
                new HttpAgentAdapter(endpoint, List.of()));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_002");
    }

    @Test
    void 服务不可达_按评估基础设施故障上抛而非记为Agent低分() {
        Path taskDir = Path.of("tasks", "api-payload-001");
        // 端口 1 是特权端口、必无监听，连接被立即拒绝；
        // （bind 后释放的临时端口在 macOS 上会黑洞化直到请求超时，不适合做不可达用例。）
        HttpAgentAdapter adapter = new HttpAgentAdapter("http://127.0.0.1:1/agent", null);

        assertThatThrownBy(() -> RunManager.run(taskDir, runsRoot, null, adapter))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("请求 http agent 失败");
    }

    /**
     * 启动本地模拟 Agent 服务。
     *
     * @param handler 请求处理器
     * @return 服务端点 URL
     * @throws IOException 端口绑定失败
     */
    private String startAgentService(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/agent";
    }
}
