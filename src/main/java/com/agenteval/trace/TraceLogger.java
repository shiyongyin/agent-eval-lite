package com.agenteval.trace;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/**
 * 全过程留痕记录器：append-only JSONL，一行一个事件。
 *
 * <p>设计取舍：评估场景事件吞吐极低（每 run 数十条），因此用同步 append + 即时 flush，
 * 换取「进程崩溃也不丢已发生事件」的可靠性；不做异步缓冲。事件序号 {@code seq} 在打开文件时
 * 以已有行数为起点递增——工具网关运行在独立进程（Agent 经 CLI 调用）也能续号；
 * 极端并发下 seq 可能重复，事件排序以 {@code timestamp} 为准（Phase 1 已知限制，
 * Phase 4 HTTP judge server 化后由单点收敛）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class TraceLogger implements AutoCloseable {

    private final Path traceFile;
    private final String runId;
    private final byte[] secret;
    private long seq;

    private TraceLogger(Path traceFile, String runId, byte[] secret, long initialSeq) {
        this.traceFile = traceFile;
        this.runId = runId;
        this.secret = secret;
        this.seq = initialSeq;
    }

    /**
     * 打开（或续写）trace 文件（不签名）。
     *
     * @param traceFile trace.jsonl 路径（父目录不存在则创建）
     * @param runId 当前 run id
     * @return 记录器实例
     */
    public static TraceLogger open(Path traceFile, String runId) {
        return open(traceFile, runId, null);
    }

    /**
     * 打开（或续写）trace 文件，并对每条事件计算 HMAC 签名。
     *
     * <p>签名让 {@code tool_call} 等「行为物证」不可被 Agent 伪造：判分侧只统计
     * 签名可核验的事件。{@code secret} 为 {@code null} 时退化为不签名（离线/测试场景）。
     *
     * @param traceFile trace.jsonl 路径（父目录不存在则创建）
     * @param runId 当前 run id
     * @param secret 每 run 签名密钥（{@code null} 表示不签名）
     * @return 记录器实例
     */
    public static TraceLogger open(Path traceFile, String runId, byte[] secret) {
        try {
            Files.createDirectories(traceFile.toAbsolutePath().getParent());
            long existing = 0;
            if (Files.exists(traceFile)) {
                try (var lines = Files.lines(traceFile, StandardCharsets.UTF_8)) {
                    existing = lines.count();
                }
            }
            return new TraceLogger(traceFile, runId, secret, existing);
        } catch (IOException e) {
            throw new UncheckedIOException("打开 trace 文件失败: " + traceFile, e);
        }
    }

    /**
     * 记录一个事件。
     *
     * @param type 事件类型
     * @param attemptId 关联 attempt（无关联传 {@code null}）
     * @param payload 事件负载（键值对；值须可被 Jackson 序列化）
     */
    public synchronized void log(TraceEventType type, String attemptId, Map<String, ?> payload) {
        seq++;
        ObjectNode event = Jsons.json().createObjectNode();
        event.put("event_id", String.format("evt_%06d", seq));
        event.put("run_id", runId);
        event.put("seq", seq);
        event.put("timestamp", Instant.now().toString());
        event.put("type", type.jsonName());
        if (attemptId != null) {
            event.put("attempt_id", attemptId);
        }
        event.set("payload", Jsons.json().valueToTree(payload == null ? Map.of() : payload));
        if (secret != null) {
            event.put(TraceSigner.SIG_FIELD, TraceSigner.sign(secret, event));
        }
        try {
            Files.writeString(traceFile,
                    Jsons.jsonCompact().writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 trace 失败: " + traceFile, e);
        }
    }

    /**
     * 读取 trace 文件的全部事件（判分与报告用）。
     *
     * @param traceFile trace.jsonl 路径
     * @return 事件节点列表；文件不存在时为空列表
     */
    public static java.util.List<JsonNode> readAll(Path traceFile) {
        if (!Files.isRegularFile(traceFile)) {
            return java.util.List.of();
        }
        try {
            return Files.readAllLines(traceFile, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        try {
                            return Jsons.json().readTree(line);
                        } catch (IOException e) {
                            throw new UncheckedIOException("trace 行解析失败", e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取 trace 失败: " + traceFile, e);
        }
    }

    @Override
    public void close() {
        // 每次写入即 flush（Files.writeString 语义），无需额外资源释放；保留接口以便未来换实现。
    }
}
