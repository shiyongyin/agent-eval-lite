package com.agenteval.tool;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 常驻工具网关的瘦客户端：{@code agent-eval tool call} 经此连到框架进程内的
 * {@link ToolGatewayServer}，由服务端代写签名 trace 事件。
 *
 * <p>客户端只转发「工具名 + 入参 + 凭证」，从不接触签名密钥——这正是「Agent 无法伪造
 * 合法工具调用」的落点。
 *
 * @author shiyongyin
 * @since 0.2.0
 */
public final class ToolGatewayClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;

    private ToolGatewayClient() {
    }

    /**
     * 向常驻服务发起一次工具调用。
     *
     * @param endpoint 形如 {@code 127.0.0.1:54321}
     * @param token 调用凭证
     * @param toolName 工具名
     * @param input 调用入参
     * @return 服务端返回的应答对象（含 {@code call_id/success/output|error}）
     * @throws IOException 连接或通信失败
     */
    public static JsonNode call(String endpoint, String token, String toolName, JsonNode input)
            throws IOException {
        String[] hostPort = endpoint.split(":", 2);
        if (hostPort.length != 2) {
            throw new IOException("非法工具网关端点: " + endpoint);
        }
        ObjectNode request = Jsons.json().createObjectNode();
        request.put("token", token);
        request.put("tool", toolName);
        request.set("input", input);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(Jsons.jsonCompact().writeValueAsString(request));
                writer.newLine();
                writer.flush();
                socket.shutdownOutput();
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("工具网关无应答");
                }
                return Jsons.json().readTree(line);
            }
        }
    }
}
