package com.agenteval.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TraceSigner} 与签名版 {@link TraceLogger} 的核验测试：
 * 这是「trace 不可伪造」（红队 P0-2）修复的信任根基，覆盖正例、篡改、伪造与错误密钥。
 */
class TraceSignerTest {

    @TempDir
    Path tempDir;

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_SECRET = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8);

    @Test
    void 签名事件写入后读回可核验() {
        Path traceFile = tempDir.resolve("traces/trace.jsonl");
        TraceLogger logger = TraceLogger.open(traceFile, "run_x", SECRET);
        logger.log(TraceEventType.TOOL_CALL, "attempt_001", Map.of(
                "call_id", "tc_real0001", "tool_name", "user.lookup", "success", true));

        List<JsonNode> events = TraceLogger.readAll(traceFile);
        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        assertThat(event.has(TraceSigner.SIG_FIELD)).isTrue();
        assertThat(TraceSigner.verify(SECRET, event)).isTrue();
        // 错误密钥无法核验。
        assertThat(TraceSigner.verify(WRONG_SECRET, event)).isFalse();
    }

    @Test
    void 篡改载荷后签名失效() {
        Path traceFile = tempDir.resolve("trace.jsonl");
        TraceLogger logger = TraceLogger.open(traceFile, "run_x", SECRET);
        logger.log(TraceEventType.TOOL_CALL, "attempt_001", Map.of(
                "call_id", "tc_real0001", "tool_name", "user.lookup", "success", false));

        JsonNode original = TraceLogger.readAll(traceFile).get(0);
        // 把 success=false 篡改为 true（模拟 Agent 想把失败调用改成成功）。
        ObjectNode tampered = ((ObjectNode) original).deepCopy();
        ((ObjectNode) tampered.get("payload")).put("success", true);
        assertThat(TraceSigner.verify(SECRET, tampered)).isFalse();
    }

    @Test
    void 无签名或伪造事件不被核验() throws Exception {
        Path traceFile = tempDir.resolve("trace.jsonl");
        TraceLogger logger = TraceLogger.open(traceFile, "run_x", SECRET);
        logger.log(TraceEventType.RUN_STARTED, null, Map.of("task_id", "t1"));

        // 模拟 Agent 直接向 trace 追加一条伪造的成功 tool_call（无合法签名）。
        String forged = "{\"event_id\":\"evt_forged\",\"run_id\":\"run_x\",\"seq\":999,"
                + "\"timestamp\":\"2026-07-06T00:00:00Z\",\"type\":\"tool_call\",\"attempt_id\":\"attempt_001\","
                + "\"payload\":{\"call_id\":\"tc_forged\",\"tool_name\":\"user.lookup\",\"success\":true}}";
        Files.writeString(traceFile, forged + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<JsonNode> events = TraceLogger.readAll(traceFile);
        JsonNode forgedEvent = events.stream()
                .filter(e -> "tc_forged".equals(e.path("payload").path("call_id").asText()))
                .findFirst().orElseThrow();
        assertThat(TraceSigner.verify(SECRET, forgedEvent)).isFalse();
        // 即便 Agent 抄一个真事件的签名贴上来，内容不同也过不了核验。
        String stolenSig = events.get(0).path(TraceSigner.SIG_FIELD).asText();
        ObjectNode forgedWithStolenSig = ((ObjectNode) forgedEvent).deepCopy();
        forgedWithStolenSig.put(TraceSigner.SIG_FIELD, stolenSig);
        assertThat(TraceSigner.verify(SECRET, forgedWithStolenSig)).isFalse();
    }

    @Test
    void 不签名模式不写签名字段_向后兼容() {
        Path traceFile = tempDir.resolve("unsigned.jsonl");
        TraceLogger logger = TraceLogger.open(traceFile, "run_x");
        logger.log(TraceEventType.TOOL_CALL, "attempt_001", Map.of("tool_name", "user.lookup"));
        JsonNode event = TraceLogger.readAll(traceFile).get(0);
        assertThat(event.has(TraceSigner.SIG_FIELD)).isFalse();
        // 无密钥时 verify 恒为 false（判分侧据此在有密钥时丢弃无签名事件）。
        assertThat(TraceSigner.verify(SECRET, event)).isFalse();
    }

    @Test
    void 每run密钥可落盘与复用_期间保持不在盘上() {
        Path runDir = tempDir.resolve("run");
        byte[] fresh = TraceSecret.obtain(runDir);
        assertThat(fresh).hasSize(32);
        // 运行期间不落盘。
        assertThat(Files.exists(runDir.resolve(".ael/trace.key"))).isFalse();

        // 收尾落盘后，离线加载得到同一把密钥。
        TraceSecret.save(runDir, fresh);
        assertThat(TraceSecret.load(runDir)).isEqualTo(fresh);

        // resume 取用会读出并删除文件，使密钥回到「仅内存」。
        byte[] reused = TraceSecret.obtain(runDir);
        assertThat(reused).isEqualTo(fresh);
        assertThat(Files.exists(runDir.resolve(".ael/trace.key"))).isFalse();
    }
}
