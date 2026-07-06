package com.agenteval.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.submission.SubmissionManager;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TraceLogger} 的 JSONL 追加、跨实例续号与事件 schema 自检测试。
 */
class TraceLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void 事件按行追加且seq递增() {
        Path traceFile = tempDir.resolve("traces/trace.jsonl");
        TraceLogger logger = TraceLogger.open(traceFile, "run_x");
        logger.log(TraceEventType.RUN_STARTED, null, Map.of("task_id", "t1"));
        logger.log(TraceEventType.AGENT_STARTED, "attempt_001", Map.of("adapter", "manual"));

        List<JsonNode> events = TraceLogger.readAll(traceFile);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).path("seq").asLong()).isEqualTo(1);
        assertThat(events.get(1).path("seq").asLong()).isEqualTo(2);
        assertThat(events.get(1).path("attempt_id").asText()).isEqualTo("attempt_001");
        assertThat(events.get(0).path("type").asText()).isEqualTo("run_started");
    }

    @Test
    void 新实例从已有行数续号_支持跨进程追加() {
        Path traceFile = tempDir.resolve("trace.jsonl");
        TraceLogger first = TraceLogger.open(traceFile, "run_x");
        first.log(TraceEventType.RUN_STARTED, null, Map.of());
        // 模拟工具网关子进程重新打开同一文件。
        TraceLogger second = TraceLogger.open(traceFile, "run_x");
        second.log(TraceEventType.TOOL_CALL, "attempt_001", Map.of("tool_name", "user.lookup"));

        List<JsonNode> events = TraceLogger.readAll(traceFile);
        assertThat(events).extracting(e -> e.path("seq").asLong()).containsExactly(1L, 2L);
    }

    @Test
    void 全部事件通过事件schema自检() throws Exception {
        Path traceFile = tempDir.resolve("trace.jsonl");
        TraceLogger logger = TraceLogger.open(traceFile, "run_x");
        logger.log(TraceEventType.SUBMISSION_RECEIVED, "attempt_001", Map.of("file", "a.json"));
        logger.log(TraceEventType.RUN_COMPLETED, null, Map.of("status", "PASSED"));

        JsonSchema schema;
        try (var in = getClass().getResourceAsStream("/schemas/trace.event.schema.json")) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Jsons.json().readTree(in));
        }
        for (String line : Files.readAllLines(traceFile)) {
            JsonNode event = Jsons.json().readTree(line);
            assertThat(SubmissionManager.validateAgainst(schema, event))
                    .as("事件应符合 trace schema: " + line)
                    .isEmpty();
        }
    }
}
