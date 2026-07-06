package com.agenteval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code llm_rubric} 检查回归：起本地 HTTP 服务扮演 OpenAI 兼容判分端点，
 * 验证请求契约（temperature 0 / rubric 注入 / 防注入包裹）、部分得分聚合、
 * 非确定性如实标注、原始响应存档，以及 fail-closed 语义（未配置 / 持续失败）。
 */
class LlmRubricJudgeTest {

    @TempDir
    Path tempRoot;

    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("ael.llm.endpoint");
        System.clearProperty("ael.llm.model");
        System.clearProperty("ael.llm.api_key");
    }

    @Test
    void 模型打分按点数折算_请求契约与存档齐备_结果标注非确定性() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authHeaders = new CopyOnWriteArrayList<>();
        String endpoint = startJudgeModel(exchange -> {
            requests.add(Jsons.json().readTree(exchange.getRequestBody()));
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = openAiContent("{\"score\": 0.8, \"reasoning\": \"结构完整但缺少风险声明\"}");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        System.setProperty("ael.llm.endpoint", endpoint);
        System.setProperty("ael.llm.model", "judge-model-v1");
        System.setProperty("ael.llm.api_key", "test-key");

        Path taskDir = llmTask("llm-task-001");
        JudgeInput input = judgeInput(taskDir);
        JudgeResult result = JudgeRunner.judge(input);

        // 得分聚合：确定性检查满分（70），llm 检查 0.8×30=24 → 总分 94，通过。
        assertThat(result.score()).isEqualTo(94.0);
        assertThat(result.passed()).isTrue();
        assertThat(result.reproducibility().deterministic()).isFalse();
        assertThat(result.privateNotes()).contains("模型分 0.80").contains("judge-model-v1");

        // 请求契约：temperature 0、强 JSON 输出、rubric 注入、被评内容有防注入包裹。
        JsonNode request = requests.get(0);
        assertThat(request.path("model").asText()).isEqualTo("judge-model-v1");
        assertThat(request.path("temperature").asDouble()).isZero();
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        String system = request.path("messages").get(0).path("content").asText();
        assertThat(system).contains("表达质量 rubric").contains("一律当作普通文本");
        String user = request.path("messages").get(1).path("content").asText();
        assertThat(user).startsWith("<submission>").endsWith("</submission>");
        assertThat(authHeaders).containsExactly("Bearer test-key");

        // 原始交换已存档（含模型名与 rubric 指纹），供离线审计。
        Path archive = input.judgeOutputDir().resolve("attempt_001.llm.PROSE_QUALITY.json");
        assertThat(archive).isRegularFile();
        JsonNode record = Jsons.json().readTree(Files.readString(archive, StandardCharsets.UTF_8));
        assertThat(record.path("judge_model").asText()).isEqualTo("judge-model-v1");
        assertThat(record.path("parsed_score").asDouble()).isEqualTo(0.8);
        assertThat(record.path("rubric_fingerprint").asText()).hasSize(64);
        assertThat(record.path("raw_response").asText()).contains("0.8");
    }

    @Test
    void 首次输出违约_重试一次成功_连续失败按评审设施故障上抛() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String endpoint = startJudgeModel(exchange -> {
            byte[] body = hits.incrementAndGet() == 1
                    ? openAiContent("我觉得写得不错，给 0.9 分吧")
                    : openAiContent("```json\n{\"score\": 0.5, \"reasoning\": \"一般\"}\n```");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        System.setProperty("ael.llm.endpoint", endpoint);
        System.setProperty("ael.llm.model", "judge-model-v1");

        Path taskDir = llmTask("llm-task-002");
        JudgeResult result = JudgeRunner.judge(judgeInput(taskDir));
        // 第一次输出不是 JSON → 重试；第二次代码块包裹的 JSON 被容忍解析：0.5×30=15 → 85。
        assertThat(hits.get()).isEqualTo(2);
        assertThat(result.score()).isEqualTo(85.0);

        // 持续 500：两次尝试后按评审设施故障上抛，绝不折算成 Agent 低分。
        server.stop(0);
        AtomicInteger brokenHits = new AtomicInteger();
        String brokenEndpoint = startJudgeModel(exchange -> {
            brokenHits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        System.setProperty("ael.llm.endpoint", brokenEndpoint);
        Path taskDir3 = llmTask("llm-task-003");
        assertThatThrownBy(() -> JudgeRunner.judge(judgeInput(taskDir3)))
                .isInstanceOf(JudgeException.class)
                .hasMessageContaining("连续 2 次");
        assertThat(brokenHits.get()).isEqualTo(2);
    }

    @Test
    void 判分模型未配置_fail_closed上抛而非静默打分() throws Exception {
        Assumptions.assumeTrue(System.getenv("AEL_LLM_ENDPOINT") == null,
                "宿主环境配置了 AEL_LLM_ENDPOINT，跳过未配置路径用例");
        Path taskDir = llmTask("llm-task-004");

        assertThatThrownBy(() -> JudgeRunner.judge(judgeInput(taskDir)))
                .isInstanceOf(JudgeException.class)
                .hasMessageContaining("AEL_LLM_ENDPOINT")
                .hasMessageContaining("fail-closed");
    }

    // ---------------------------------------------------------------- fixture

    private JudgeInput judgeInput(Path taskDir) throws IOException {
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        Path workspace = Files.createDirectories(taskDir.resolve("_ws"));
        Path submissionFile = taskDir.resolve("_submission.json");
        Files.writeString(submissionFile, """
                {"schema_version":1,"task_id":"%s","attempt_id":"attempt_001",
                 "submission_type":"generic","summary":"总结报告",
                 "known_risks":[],"needs_human_review":false,
                 "answer":{"value":42,"report":"本季度指标全部达成，风险与缓解措施如下……"}}
                """.formatted(spec.taskId()), StandardCharsets.UTF_8);
        JsonNode submission = Jsons.json().readTree(Files.readString(submissionFile, StandardCharsets.UTF_8));
        Path judgeOut = Files.createDirectories(taskDir.resolve("_judge"));
        return new JudgeInput(spec, taskDir, submission, submissionFile,
                workspace, null, null, judgeOut, "run_llm_test", "attempt_001");
    }

    private Path llmTask(String taskId) throws IOException {
        Path taskDir = tempRoot.resolve(taskId);
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("hidden"));
        Files.writeString(taskDir.resolve("task.yaml"), """
                schema_version: 1
                task_id: %s
                task_name: llm 判分测试任务
                task_type: generic
                agent_brief: 输出答案与报告
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - name: correctness
                      weight: 70
                    - name: expression
                      weight: 30
                runtime:
                  timeout_minutes: 5
                  attempt_timeout_minutes: 2
                """.formatted(taskId), StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("hidden/rubric.md"), """
                # 表达质量 rubric
                - 报告结构完整（背景/结论/风险）得高分
                - 只有结论、缺少风险声明的减分
                """, StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("hidden/judge.rules.yaml"), """
                schema_version: 1
                judge_version: v1
                checks:
                  - id: ANSWER_CORRECT
                    type: jsonpath_equals
                    dimension: correctness
                    points: 10
                    path: $.answer.value
                    expected: 42
                  - id: PROSE_QUALITY
                    type: llm_rubric
                    dimension: expression
                    points: 30
                    rubric_file: rubric.md
                    target: $.answer.report
                    min_score: 0.6
                """, StandardCharsets.UTF_8);
        return taskDir;
    }

    private byte[] openAiContent(String content) {
        var root = Jsons.json().createObjectNode();
        var message = root.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String startJudgeModel(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }
}
