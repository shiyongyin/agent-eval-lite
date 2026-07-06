package com.agenteval.tool;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 常驻工具网关服务：把「调用工具」从 Agent 自跑的独立进程收敛为框架进程内的单点服务。
 *
 * <p>为什么必须常驻（红队 P0-2 的根因修复）：旧设计里 {@code agent-eval tool call} 由 Agent
 * 派生、直接向 {@code trace.jsonl} 追加事件——Agent 因而能凭空伪造「成功调用」骗过判分。
 * 改为常驻服务后，<strong>只有本服务持有 trace 签名密钥并代写事件</strong>，Agent 无从伪造合法签名；
 * 它顶多能往 trace 追加无签名的假事件，而判分侧只认可核验签名的事件，假事件被丢弃。
 *
 * <p>监听 127.0.0.1 的临时端口，一连接一请求：请求 / 应答均为单行 JSON。
 * {@code token} 仅作纵深防御（阻挡本机无关进程误连），安全性不依赖它——真正的保证是密钥留在本进程内存。
 *
 * @author shiyongyin
 * @since 0.2.0
 */
public final class ToolGatewayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayServer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ToolGateway gateway;
    private final String token;
    private final ServerSocket serverSocket;
    private final ExecutorService pool;
    private final Thread acceptThread;
    private volatile boolean running;

    private ToolGatewayServer(ToolGateway gateway, String token, ServerSocket serverSocket) {
        this.gateway = gateway;
        this.token = token;
        this.serverSocket = serverSocket;
        AtomicInteger counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "tool-gateway-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.acceptThread = new Thread(this::acceptLoop, "tool-gateway-accept");
        this.acceptThread.setDaemon(true);
    }

    /**
     * 启动服务（绑定 loopback 临时端口并开始接收连接）。
     *
     * @param gateway 绑定了签名 trace 的网关
     * @return 已启动的服务实例
     */
    public static ToolGatewayServer start(ToolGateway gateway) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            String token = HexFormat.of().formatHex(randomBytes());
            ToolGatewayServer server = new ToolGatewayServer(gateway, token, ss);
            server.running = true;
            server.acceptThread.start();
            log.debug("工具网关服务已启动: {}", server.endpoint());
            return server;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("工具网关服务启动失败", e);
        }
    }

    /** @return 客户端连接端点，形如 {@code 127.0.0.1:54321} */
    public String endpoint() {
        return serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
    }

    /** @return 客户端调用凭证（注入 Agent 环境的 {@code AEL_TOOL_TOKEN}） */
    public String token() {
        return token;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    log.debug("工具网关 accept 中断: {}", e.getMessage());
                }
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            ObjectNode response = process(line);
            writer.write(Jsons.jsonCompact().writeValueAsString(response));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.debug("工具网关请求处理失败: {}", e.getMessage());
        }
    }

    private ObjectNode process(String line) {
        ObjectNode response = Jsons.json().createObjectNode();
        if (line == null || line.isBlank()) {
            return response.put("success", false).put("error", "empty_request");
        }
        JsonNode request;
        try {
            request = Jsons.json().readTree(line);
        } catch (IOException e) {
            return response.put("success", false).put("error", "malformed_request");
        }
        if (!token.equals(request.path("token").asText())) {
            return response.put("success", false).put("error", "unauthorized");
        }
        String toolName = request.path("tool").asText();
        ToolCallResult result = gateway.call(toolName, request.path("input"));
        response.put("call_id", result.callId());
        response.put("success", result.success());
        if (result.success()) {
            response.set("output", result.output());
        } else {
            response.put("error", result.error());
        }
        return response;
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.debug("关闭工具网关 socket 失败: {}", e.getMessage());
        }
        pool.shutdownNow();
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
