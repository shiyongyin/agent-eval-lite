package com.agenteval.tool;

import com.agenteval.task.TaskSpec;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具真实 HTTP 后端执行器（live 模式专用）：按任务静态声明外呼，并把每次交换
 * 以 mock 应答库同格式存档——「真实调用」与「可复现」在此闭合。
 *
 * <p>白名单与防走私口径：
 * <ul>
 *   <li>URL / method / headers 全部来自 {@code task.yaml} 的静态声明，Agent 入参只作为
 *       请求负载（POST 请求体 / GET 查询参数），不存在任何拼 URL 的通道；</li>
 *   <li>不跟随重定向——后端把请求弹去别的主机不会被隐式接受；</li>
 *   <li>header 值支持 {@code ${ENV:NAME}} 占位符，凭证从框架进程环境解析后注入，
 *       Agent 侧与任务文件中永不出现明文凭证；占位符解析失败按调用失败处理（fail-closed）；</li>
 *   <li>响应体设 8MB 上限，防恶意/失控后端拖垮框架。</li>
 * </ul>
 *
 * <p>失败语义：后端不可达、非 2xx、响应非 JSON 均收敛为<strong>结构化调用失败</strong>回给
 * Agent（trace 同步留痕），而不是让整个 run 崩溃——网关服务线程里抛异常只会让 Agent 端
 * 连接中断，达不到「run 级基础设施故障」的效果；评估方应经 report 的 failed_calls
 * 与 trace 识别后端故障，避免把基础设施问题误读为 Agent 低分。
 *
 * <p>存档（{@code <run>/tools/<name>.recorded.yaml}）与应答库同构：
 * <pre>{@code
 * responses:
 *   - match: {user_id: "u_1001"}   # 即当轮入参
 *     response: {...}              # 即后端真实响应
 * }</pre>
 * 复制到任务的 {@code hidden/tools/<name>.responses.yaml} 即完成「录制 → 回放」晋升，
 * 后续 CI 在默认 replay 模式下确定性重放。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
final class ToolHttpBackend {

    private static final Logger log = LoggerFactory.getLogger(ToolHttpBackend.class);
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{ENV:([A-Za-z_][A-Za-z0-9_]*)}");
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final Path archiveDir;
    private final HttpClient client;

    /**
     * 构造执行器。
     *
     * @param archiveDir 响应存档目录（通常为 {@code <run>/tools}）
     */
    ToolHttpBackend(Path archiveDir) {
        this.archiveDir = archiveDir;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 单次调用结果：成功携带响应节点，失败携带错误码文案（二者互斥）。
     *
     * @param response 后端 JSON 响应（失败时为 {@code null}）
     * @param error 错误描述（成功时为 {@code null}）
     */
    record Result(JsonNode response, String error) {

        static Result ok(JsonNode response) {
            return new Result(response, null);
        }

        static Result fail(String error) {
            return new Result(null, error);
        }
    }

    /**
     * 真实外呼一次，并把成功交换写入存档。
     *
     * @param toolName 工具名（决定存档文件名）
     * @param backend 任务声明的后端
     * @param input Agent 调用入参
     * @return 调用结果
     */
    Result call(String toolName, TaskSpec.HttpBackend backend, JsonNode input) {
        HttpRequest request;
        try {
            request = buildRequest(backend, input);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }

        int status;
        byte[] body;
        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            body = readCapped(response.body());
        } catch (IOException e) {
            log.warn("工具 {} 后端外呼失败: {}", toolName, e.toString());
            return Result.fail("backend_unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("backend_interrupted");
        }

        if (status < 200 || status >= 300) {
            return Result.fail("backend_http_" + status);
        }
        JsonNode parsed;
        try {
            parsed = Jsons.json().readTree(body);
        } catch (IOException e) {
            return Result.fail("backend_response_not_json: " + e.getMessage());
        }
        archive(toolName, input, parsed);
        return Result.ok(parsed);
    }

    // ------------------------------------------------------------- request

    private HttpRequest buildRequest(TaskSpec.HttpBackend backend, JsonNode input) {
        String url = backend.url();
        HttpRequest.Builder builder;
        if ("GET".equals(backend.method())) {
            builder = HttpRequest.newBuilder(URI.create(url + queryString(input))).GET();
        } else {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(input.toString(), StandardCharsets.UTF_8));
        }
        builder.timeout(Duration.ofSeconds(backend.timeoutSeconds()));
        for (Map.Entry<String, String> header : backend.headers().entrySet()) {
            builder.header(header.getKey(), resolveEnvPlaceholders(header.getKey(), header.getValue()));
        }
        return builder.build();
    }

    /** GET 语义：入参顶层标量字段编码为查询参数；出现嵌套结构直接拒绝（无法无损映射）。 */
    private static String queryString(JsonNode input) {
        if (input == null || input.isNull() || input.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("&", "?", "");
        var fields = input.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isValueNode()) {
                throw new IllegalArgumentException(
                        "backend_get_input_not_flat: 字段 " + field.getKey() + " 不是标量，GET 后端要求扁平入参");
            }
            joiner.add(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(field.getValue().asText(), StandardCharsets.UTF_8));
        }
        return joiner.toString();
    }

    private static String resolveEnvPlaceholders(String headerName, String value) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String env = System.getenv(matcher.group(1));
            if (env == null || env.isBlank()) {
                throw new IllegalArgumentException(
                        "backend_header_env_missing: header " + headerName + " 需要环境变量 " + matcher.group(1));
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(env));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static byte[] readCapped(InputStream stream) throws IOException {
        try (stream) {
            byte[] data = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (data.length > MAX_RESPONSE_BYTES) {
                throw new IOException("后端响应超过 " + MAX_RESPONSE_BYTES + " 字节上限");
            }
            return data;
        }
    }

    // ------------------------------------------------------------- archive

    /**
     * 把一次成功交换追加进存档（读-改-写整体重写，保持文件是合法 YAML；低频调用可承受）。
     * 同一入参重复出现时保留全部记录以供审计——replay 取首个命中，确定性不受影响。
     * 存档失败只告警不失败调用：外呼已真实发生，事实以 trace 为准。
     */
    private synchronized void archive(String toolName, JsonNode input, JsonNode response) {
        try {
            Files.createDirectories(archiveDir);
            Path file = archiveDir.resolve(toolName + ".recorded.yaml");
            ObjectNode root;
            if (Files.isRegularFile(file)) {
                root = (ObjectNode) Jsons.yaml().readTree(Files.readString(file, StandardCharsets.UTF_8));
            } else {
                root = Jsons.yaml().createObjectNode();
            }
            ArrayNode responses = root.withArray("responses");
            ObjectNode entry = responses.addObject();
            entry.set("match", input.deepCopy());
            entry.set("response", response.deepCopy());
            Files.writeString(file, Jsons.yaml().writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (IOException | ClassCastException e) {
            log.warn("工具 {} 响应存档写入失败（外呼结果不受影响）: {}", toolName, e.toString());
        }
    }
}
