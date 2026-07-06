package com.agenteval.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.judge.RulesFile;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 规则深度 lint 回归：把「run 时才会炸的规则配置错误」（expected_from 断链、schema_file 缺失、
 * 要求调用白名单外工具）前移到 validate 静态阶段拦截；内置任务必须全部通过 lint。
 */
class ValidateLintTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"code-fix-001", "api-payload-001", "doc-analysis-001", "tool-call-001", "prd-review-001"})
    void 内置任务全部通过深度lint(String taskId) {
        Path taskDir = Path.of("tasks", taskId);
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        RulesFile rules = loadRules(spec, taskDir);

        assertThat(ValidateCommand.lintRules(spec, taskDir, rules)).isEmpty();
    }

    @Test
    void expected_from断链与schema_file缺失_被静态拦截() throws Exception {
        Path taskDir = brokenTask("""
                schema_version: 1
                judge_version: "0.1.0"
                checks:
                  - id: MISSING_FILE
                    type: jsonpath_equals
                    dimension: correctness
                    points: 40
                    path: "$.answer.value"
                    expected_from: "expected/nope.json#/value"
                    feedback_fail: 占位
                  - id: BAD_POINTER
                    type: jsonpath_equals
                    dimension: correctness
                    points: 30
                    path: "$.answer.other"
                    expected_from: "expected/answer.json#/no_such_key"
                    feedback_fail: 占位
                  - id: MISSING_SCHEMA
                    type: json_schema
                    dimension: correctness
                    points: 30
                    target: "$.answer"
                    schema_file: expected/nope.schema.json
                    feedback_fail: 占位
                """, List.of());
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        RulesFile rules = loadRules(spec, taskDir);

        List<String> errors = ValidateCommand.lintRules(spec, taskDir, rules);

        assertThat(errors).hasSize(3);
        assertThat(errors.get(0)).contains("MISSING_FILE").contains("expected_from 文件不存在");
        assertThat(errors.get(1)).contains("BAD_POINTER").contains("指针不可解析");
        assertThat(errors.get(2)).contains("MISSING_SCHEMA").contains("schema_file 不存在");
    }

    @Test
    void 要求调用白名单外工具_被静态拦截() throws Exception {
        Path taskDir = brokenTask("""
                schema_version: 1
                judge_version: "0.1.0"
                checks:
                  - id: TOOL_NOT_ALLOWED
                    type: tool_call_required
                    dimension: correctness
                    points: 50
                    tool: card.create
                    feedback_fail: 占位
                  - id: STATE_TOOL_NOT_ALLOWED
                    type: world_state
                    dimension: correctness
                    points: 50
                    tools: [card.create]
                    expected: []
                    feedback_fail: 占位
                """, List.of("user.lookup"));
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        RulesFile rules = loadRules(spec, taskDir);

        List<String> errors = ValidateCommand.lintRules(spec, taskDir, rules);

        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).contains("TOOL_NOT_ALLOWED").contains("不在 allowed_tools");
        assertThat(errors.get(1)).contains("STATE_TOOL_NOT_ALLOWED").contains("不在 allowed_tools");
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * 生成一个规格合法但规则引用有病的最小任务。
     *
     * @param rulesYaml 规则文件内容
     * @param allowedTools 任务白名单工具
     * @return 任务目录
     * @throws Exception 文件写入失败
     */
    private Path brokenTask(String rulesYaml, List<String> allowedTools) throws Exception {
        Path taskDir = dir.resolve("broken-task-001");
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("hidden/expected"));
        for (String tool : allowedTools) {
            // 规格加载要求白名单工具具备 mock 应答库，这里补齐最小占位。
            Path fixture = taskDir.resolve("hidden/tools/" + tool + ".responses.yaml");
            Files.createDirectories(fixture.getParent());
            Files.writeString(fixture, "responses: []\n");
        }
        String tools = allowedTools.isEmpty() ? "allowed_tools: []"
                : "allowed_tools:\n" + allowedTools.stream()
                        .map(t -> "  - name: " + t + "\n    description: 测试工具")
                        .collect(Collectors.joining("\n"));
        Files.writeString(taskDir.resolve("task.yaml"), """
                schema_version: 1
                task_id: broken-task-001
                task_name: lint 测试任务
                task_type: generic
                description: 仅供 lint 单测使用
                agent_brief: 占位说明
                visible_context: []
                %s
                submit:
                  format: json
                  schema: builtin:generic
                  max_attempts: 1
                  cooldown_seconds: 0
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                  feedback:
                    level: failed_rules
                    include_scores: true
                scoring:
                  max_score: 100
                  pass_score: 80
                  selection: best_score
                  dimensions:
                    - name: correctness
                      weight: 100
                runtime:
                  timeout_minutes: 5
                  attempt_timeout_minutes: 5
                """.formatted(tools));
        Files.writeString(taskDir.resolve("hidden/judge.rules.yaml"), rulesYaml);
        Files.writeString(taskDir.resolve("hidden/expected/answer.json"), "{\"value\": \"x\"}");
        return taskDir;
    }

    private static RulesFile loadRules(TaskSpec spec, Path taskDir) {
        return RulesFile.load(
                taskDir.resolve(spec.judge().rulesFile()),
                spec.scoring().dimensions().stream()
                        .map(TaskSpec.Dimension::name)
                        .collect(Collectors.toSet()));
    }
}
