package com.agenteval.submission;

import com.agenteval.task.TaskSpec;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提交管理器：Agent 结构化提交的守门人。
 *
 * <p>校验分四步，任一步失败即整体失败并给出可读错误（这些错误会作为反馈回给 Agent，
 * 帮它修正格式而非泄露评分规则）：
 * <ol>
 *   <li>JSON 可解析；</li>
 *   <li>信封 schema（必填字段、attempt_id 形态、类型枚举）；</li>
 *   <li>上下文绑定（task_id / attempt_id 与当前 run 匹配，防串题与乱序提交）；</li>
 *   <li>分型 schema（按 task.yaml 的 submit.schema 选择：{@code builtin:<type>} 或任务自带文件）。</li>
 * </ol>
 *
 * <p>自然语言输出不经过本类——不进入 inbox 的内容对评分不存在（设计原则 #1/#2）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class SubmissionManager {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, JsonSchema> BUILTIN_CACHE = new ConcurrentHashMap<>();

    /**
     * 提交文件体积上限（字节）。结构化提交本应短小精悍；超限即判非法，
     * 避免恶意超大 payload 拖垮判分进程（解析期 OOM / GC 抖动）。默认 256 KiB。
     */
    static final long MAX_SUBMISSION_BYTES = 256L * 1024;

    private SubmissionManager() {
    }

    /**
     * 校验一份提交文件。
     *
     * @param submissionFile 提交 JSON 文件
     * @param spec 任务规格
     * @param taskDir 任务目录（解析任务自带 schema 用）
     * @param expectedAttemptId 期望的 attempt id；离线判分场景可传 {@code null} 跳过绑定校验
     * @return 校验结论
     */
    public static SubmissionValidationResult validate(
            Path submissionFile, TaskSpec spec, Path taskDir, String expectedAttemptId) {
        try {
            long size = Files.size(submissionFile);
            if (size > MAX_SUBMISSION_BYTES) {
                return SubmissionValidationResult.fail(List.of(
                        "提交体积超限: " + size + " 字节，上限 " + MAX_SUBMISSION_BYTES
                                + " 字节（结构化提交应短小；超大 payload 判为非法）"), null);
            }
        } catch (IOException e) {
            return SubmissionValidationResult.fail(List.of("无法读取提交文件: " + e.getMessage()), null);
        }

        JsonNode submission;
        try {
            submission = Jsons.json().readTree(Files.readString(submissionFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return SubmissionValidationResult.fail(List.of("提交不是合法 JSON: " + e.getMessage()), null);
        }

        List<String> errors = new ArrayList<>(validateAgainst(envelopeSchema(), submission));

        if (errors.isEmpty()) {
            if (!spec.taskId().equals(submission.path("task_id").asText())) {
                errors.add("task_id 与当前任务不匹配: 期望 " + spec.taskId()
                        + "，实际 " + submission.path("task_id").asText());
            }
            if (expectedAttemptId != null
                    && !expectedAttemptId.equals(submission.path("attempt_id").asText())) {
                errors.add("attempt_id 与当前轮次不匹配: 期望 " + expectedAttemptId
                        + "，实际 " + submission.path("attempt_id").asText());
            }
        }

        if (errors.isEmpty()) {
            JsonSchema typedSchema = resolveTypedSchema(spec, taskDir);
            errors.addAll(validateAgainst(typedSchema, submission));
        }
        if (errors.isEmpty()) {
            errors.addAll(validateKnownTopLevelFields(submission, spec));
        }

        if (!errors.isEmpty()) {
            return SubmissionValidationResult.fail(errors, submission);
        }
        return SubmissionValidationResult.ok(submission, submission.path("submission_type").asText());
    }

    /**
     * 加载框架内置 schema（带缓存）。
     *
     * @param name 内置名（如 {@code api_payload}）
     * @return schema 实例
     */
    public static JsonSchema builtinSchema(String name) {
        return BUILTIN_CACHE.computeIfAbsent(name, key -> {
            String resource = "/schemas/submission." + key + ".schema.json";
            try (InputStream in = SubmissionManager.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalArgumentException("内置 schema 不存在: " + resource);
                }
                return FACTORY.getSchema(Jsons.json().readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException("读取内置 schema 失败: " + resource, e);
            }
        });
    }

    /**
     * 用任意 schema 校验任意节点，返回可读错误清单。
     *
     * @param schema schema 实例
     * @param node 待校验节点
     * @return 错误清单；合法时为空
     */
    public static List<String> validateAgainst(JsonSchema schema, JsonNode node) {
        Set<ValidationMessage> messages = schema.validate(node);
        return messages.stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    /**
     * 从文件加载 schema（任务自带 schema 与 judge 的 json_schema check 共用）。
     *
     * @param schemaFile schema 文件路径
     * @return schema 实例
     */
    public static JsonSchema schemaFromFile(Path schemaFile) {
        try {
            return FACTORY.getSchema(Jsons.json().readTree(Files.readString(schemaFile, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取 schema 文件失败: " + schemaFile, e);
        }
    }

    private static JsonSchema envelopeSchema() {
        return builtinSchema("envelope");
    }

    private static JsonSchema resolveTypedSchema(TaskSpec spec, Path taskDir) {
        String schemaRef = spec.submit().schema();
        if (schemaRef.startsWith("builtin:")) {
            return builtinSchema(schemaRef.substring("builtin:".length()));
        }
        return schemaFromFile(taskDir.resolve(schemaRef));
    }

    private static List<String> validateKnownTopLevelFields(JsonNode submission, TaskSpec spec) {
        String schemaRef = spec.submit().schema();
        if (!schemaRef.startsWith("builtin:")) {
            return List.of();
        }
        String type = schemaRef.substring("builtin:".length());
        Set<String> allowed = new HashSet<>(Set.of(
                "schema_version", "task_id", "attempt_id", "submission_type", "summary",
                "evidence", "tool_calls_used", "usage", "known_risks", "needs_human_review"));
        switch (type) {
            case "generic" -> allowed.add("answer");
            case "code_fix" -> allowed.addAll(Set.of("changed_files", "tests_run"));
            case "api_payload" -> allowed.add("final_payload");
            case "tool_call" -> allowed.addAll(Set.of("final_payload", "validation_result"));
            case "document", "review" -> allowed.addAll(Set.of("deliverable", "sources", "confidence"));
            default -> {
                return List.of();
            }
        }

        Set<String> unknown = new LinkedHashSet<>();
        Iterator<String> fields = submission.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        }
        if (unknown.isEmpty()) {
            return List.of();
        }
        return List.of("提交包含未授权顶层字段: " + unknown);
    }
}
