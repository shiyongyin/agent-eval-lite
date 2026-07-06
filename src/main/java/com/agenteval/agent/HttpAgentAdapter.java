package com.agenteval.agent;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Agent 适配器：评估「服务形态」的 Agent（chat API、Agent 平台的 HTTP 入口、自研服务等）。
 *
 * <p>窄口径契约（框架推任务、收提交，不引入 SSE/浏览器等重协议面）：每轮 attempt 框架向
 * {@code endpoint} 发送一次 {@code POST}（{@code Content-Type: application/json}），请求体为：
 * <pre>{@code
 * {
 *   "protocol": "ael-http-agent/1",
 *   "task_id": "...", "attempt_id": "attempt_001", "attempt_number": 1, "max_attempts": 3,
 *   "instructions": "<instructions.md 全文>",
 *   "feedback": <上一轮受控反馈 JSON，首轮为 null>,
 *   "workspace_dir": "...", "inbox_dir": "...", "run_dir": "...",   // 同机服务可直接读写工作区
 *   "tool_gateway": {"endpoint": "127.0.0.1:PORT", "token": "..."}  // 经网关调工具（见 ToolGatewayClient 行协议）
 * }
 * }</pre>
 *
 * <p>响应约定：
 * <ul>
 *   <li>{@code 200} + JSON 响应体：响应体即本轮提交信封，适配器代写进 {@code inbox/}
 *       （后续 schema 校验、判分与反馈与其他适配器完全同链路）；</li>
 *   <li>{@code 204}：Agent 明确放弃后续轮次（run 按 {@code agent_exhausted} 收束）；</li>
 *   <li>其他状态码 / 请求超时：本轮无提交，框架写入无效轮反馈后继续下一轮（预算控制权在框架）；</li>
 *   <li>连接失败（服务不可达）：按评估基础设施故障抛出，与 CLI 适配器「进程无法启动」同语义——
 *       避免把服务宕机误记为 Agent 低分。</li>
 * </ul>
 *
 * <p>安全边界：远端服务拿到的只有任务说明、反馈与工作区路径；hidden 目录、判分规则与
 * trace 签名密钥永不进入请求体。响应体大小设有上限，防止恶意服务用超大应答耗尽框架内存
 * （超限按本轮无提交处理；正常大小的非法提交仍交由提交契约拒绝）。
 *
 * @author shiyongyin
 * @since 0.3.0
 */
public final class HttpAgentAdapter implements AgentAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentAdapter.class);

    /** 响应体大小上限（字节）：远大于提交契约的合法上限，仅防内存耗尽型滥用。 */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final String endpoint;
    private final List<String> extraHeaders;
    private final HttpClient client;

    /**
     * 构造适配器。
     *
     * @param endpoint Agent 服务端点 URL（框架按轮 POST）
     * @param extraHeaders 附加请求头（形如 {@code "Authorization: Bearer xxx"}；可为 {@code null}）
     */
    public HttpAgentAdapter(String endpoint, List<String> extraHeaders) {
        this.endpoint = endpoint;
        this.extraHeaders = extraHeaders == null ? List.of() : List.copyOf(extraHeaders);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public AttemptOutcome runAttempt(AttemptInput input) {
        Path logFile = input.context().agentLogsDir().resolve(input.attemptId() + ".log");
        HttpRequest request = buildRequest(input);

        int status;
        byte[] body;
        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            body = readCapped(response.body());
        } catch (HttpConnectTimeoutException e) {
            // 连接都建立不起来 = 服务不可达，走基础设施故障路径（见下方 IOException 分支）。
            throw new UncheckedIOException("请求 http agent 失败: " + endpoint, e);
        } catch (HttpTimeoutException e) {
            // 应答超时与 CLI 适配器超时强杀同语义：本轮作废但 run 继续，预算控制权在框架。
            log.warn("http agent 超时（{}s）: {}", input.timeout().toSeconds(), endpoint);
            appendLog(logFile, "TIMEOUT after " + input.timeout().toSeconds() + "s: " + e.getMessage());
            return new AttemptOutcome(null, false, -1, logFile, false);
        } catch (IOException e) {
            // 服务不可达 ≠ Agent 低分：按基础设施故障上抛（与 CLI 进程启动失败一致）。
            throw new UncheckedIOException("请求 http agent 失败: " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 http agent 应答被中断", e);
        }

        appendLog(logFile, "POST " + endpoint + " -> " + status + "（响应 " + body.length + " 字节）");

        if (status == 204) {
            return AttemptOutcome.noMoreInput();
        }
        if (status < 200 || status >= 300 || body.length == 0) {
            log.warn("http agent 本轮未产出提交（status={}, bytes={}）", status, body.length);
            return new AttemptOutcome(null, false, status, logFile, false);
        }

        Path target = input.expectedSubmissionFile();
        try {
            Files.write(target, body);
        } catch (IOException e) {
            throw new UncheckedIOException("写入提交文件失败: " + target, e);
        }
        return new AttemptOutcome(target, true, 0, logFile, false);
    }

    private HttpRequest buildRequest(AttemptInput input) {
        ObjectNode payload = Jsons.json().createObjectNode();
        payload.put("protocol", "ael-http-agent/1");
        payload.put("task_id", input.context().spec().taskId());
        payload.put("attempt_id", input.attemptId());
        payload.put("attempt_number", input.attemptNumber());
        payload.put("max_attempts", input.context().spec().submit().maxAttempts());
        payload.put("instructions", readText(input.instructionsFile()));
        payload.set("feedback", readFeedback(input.previousFeedbackFile()));
        payload.put("workspace_dir", input.context().workspaceDir().toAbsolutePath().toString());
        payload.put("inbox_dir", input.context().inboxDir().toAbsolutePath().toString());
        payload.put("run_dir", input.context().runDir().toAbsolutePath().toString());
        if (input.toolAccess() != null) {
            ObjectNode tool = payload.putObject("tool_gateway");
            tool.put("endpoint", input.toolAccess().endpoint());
            tool.put("token", input.toolAccess().token());
        }

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(input.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Jsons.jsonCompact().writeValueAsString(payload), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("序列化 http agent 请求失败", e);
        }
        for (String header : extraHeaders) {
            int sep = header.indexOf(':');
            if (sep <= 0) {
                throw new IllegalArgumentException("非法请求头（应形如 'Name: value'）: " + header);
            }
            builder.header(header.substring(0, sep).trim(), header.substring(sep + 1).trim());
        }
        return builder.build();
    }

    private static JsonNode readFeedback(Path feedbackFile) {
        if (feedbackFile == null) {
            return NullNode.getInstance();
        }
        try {
            return Jsons.json().readTree(Files.readString(feedbackFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取反馈文件失败: " + feedbackFile, e);
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取任务说明失败: " + file, e);
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) {
                log.warn("http agent 响应超过 {} 字节上限，本轮按无提交处理", MAX_RESPONSE_BYTES);
                return new byte[0];
            }
            return body;
        }
    }

    private static void appendLog(Path logFile, String line) {
        try {
            Files.writeString(logFile, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写 agent 日志失败: {}", logFile, e);
        }
    }
}
