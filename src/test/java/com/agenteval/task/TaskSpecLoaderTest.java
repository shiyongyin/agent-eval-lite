package com.agenteval.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TaskSpecLoader} 的加载、默认值与 fail-fast 校验测试。
 */
class TaskSpecLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void 加载真实示例任务并应用默认值() {
        TaskSpec spec = TaskSpecLoader.load(Path.of("tasks", "api-payload-001"));
        assertThat(spec.taskId()).isEqualTo("api-payload-001");
        assertThat(spec.taskType()).isEqualTo(TaskType.API_PAYLOAD);
        assertThat(spec.judge().type()).isEqualTo(JudgeType.RULES);
        assertThat(spec.judge().feedback().level()).isEqualTo(FeedbackLevel.FAILED_RULES);
        assertThat(spec.scoring().selection()).isEqualTo(SelectionPolicy.BEST_SCORE);
        assertThat(spec.scoring().dimensions()).hasSize(4);
        assertThat(spec.submit().maxAttempts()).isEqualTo(3);
    }

    @Test
    void 未声明分层时默认regression且标签为空() throws Exception {
        Path taskDir = writeTask("no-tier", """
                schema_version: 1
                task_id: no-tier
                task_name: 无分层任务
                task_type: generic
                agent_brief: 做点什么
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: a, weight: 100}
                """);
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        assertThat(spec.tier()).isEqualTo(TaskTier.REGRESSION);
        assertThat(spec.labels()).isEmpty();
    }

    @Test
    void 解析显式分层与标签() throws Exception {
        Path taskDir = writeTask("tiered", """
                schema_version: 1
                task_id: tiered
                task_name: 带分层任务
                task_type: generic
                tier: security
                labels: [redteam, tool-discipline]
                agent_brief: 做点什么
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: a, weight: 100}
                """);
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        assertThat(spec.tier()).isEqualTo(TaskTier.SECURITY);
        assertThat(spec.labels()).containsExactly("redteam", "tool-discipline");
    }

    @Test
    void 非法分层取值被拒绝() throws Exception {
        Path taskDir = writeTask("bad-tier", """
                schema_version: 1
                task_id: bad-tier
                task_name: 分层非法
                task_type: generic
                tier: turbo
                agent_brief: 做点什么
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: a, weight: 100}
                """);
        assertThatThrownBy(() -> TaskSpecLoader.load(taskDir))
                .isInstanceOf(TaskSpecException.class)
                .hasMessageContaining("tier 取值非法");
    }

    @Test
    void 权重和不等于满分时聚合报错() throws Exception {
        Path taskDir = writeTask("bad-weights", """
                schema_version: 1
                task_id: bad-weights
                task_name: 坏任务
                task_type: generic
                agent_brief: 做点什么
                visible_context: []
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: a, weight: 30}
                    - {name: b, weight: 30}
                """);
        assertThatThrownBy(() -> TaskSpecLoader.load(taskDir))
                .isInstanceOf(TaskSpecException.class)
                .hasMessageContaining("权重之和");
    }

    @Test
    void 结构层缺失的多个必填字段一次性报出() throws Exception {
        Path taskDir = writeTask("broken", """
                schema_version: 1
                task_id: broken
                agent_brief: x 任务
                judge: {}
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: a, weight: 100}
                """);
        assertThatThrownBy(() -> TaskSpecLoader.load(taskDir))
                .isInstanceOf(TaskSpecException.class)
                .hasMessageContaining("task_name")
                .hasMessageContaining("task_type")
                .hasMessageContaining("type");
    }

    @Test
    void 引用层的多个断链一次性报出() throws Exception {
        Path taskDir = writeTask("broken-refs", """
                schema_version: 1
                task_id: broken-refs
                task_name: 引用断链任务
                task_type: generic
                agent_brief: x 任务
                visible_context:
                  - work/missing.md
                judge:
                  type: rules
                  rules_file: hidden/not-there.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: a, weight: 100}
                """);
        assertThatThrownBy(() -> TaskSpecLoader.load(taskDir))
                .isInstanceOf(TaskSpecException.class)
                .hasMessageContaining("visible_context 引用的文件不存在")
                .hasMessageContaining("rules_file 不存在");
    }

    @Test
    void 目录名与taskId不一致被拒绝() throws Exception {
        Path taskDir = writeTask("dir-name", """
                schema_version: 1
                task_id: other-name
                task_name: 名称不一致
                task_type: generic
                agent_brief: 做点什么
                judge:
                  type: rules
                  rules_file: hidden/judge.rules.yaml
                scoring:
                  max_score: 100
                  pass_score: 80
                  dimensions:
                    - {name: a, weight: 100}
                """);
        assertThatThrownBy(() -> TaskSpecLoader.load(taskDir))
                .isInstanceOf(TaskSpecException.class)
                .hasMessageContaining("必须与任务目录名");
    }

    private Path writeTask(String dirName, String yaml) throws Exception {
        Path taskDir = tempDir.resolve(dirName);
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("hidden"));
        // 提供一个合法规则文件，让只想验证其他错误的用例不被 rules_file 缺失干扰。
        Files.writeString(taskDir.resolve("hidden/judge.rules.yaml"), """
                schema_version: 1
                judge_version: "1.0.0"
                checks:
                  - {id: A, type: jsonpath_exists, dimension: a, points: 100, path: "$.answer"}
                """, StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("task.yaml"), yaml, StandardCharsets.UTF_8);
        return taskDir;
    }
}
