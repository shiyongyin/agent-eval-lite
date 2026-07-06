package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.agent.ScriptedAgentAdapter;
import com.agenteval.cli.Main;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code task init} 脚手架端到端回归：生成的任务必须<strong>开箱即用</strong>——
 * 静态体检（validate + 深度 lint）通过，且 scripted 回放走完「失败 → 受控反馈 → 修正通过」闭环。
 * 模板质量在这里被当作产品能力锁死：任何人改坏模板会立刻在本测试暴露。
 */
class TaskInitScaffoldTest {

    @TempDir
    Path root;

    @Test
    void 生成的任务开箱通过validate与回放闭环() throws Exception {
        Path tasksRoot = root.resolve("tasks");
        int initExit = new CommandLine(new Main()).execute(
                "task", "init", "--id", "demo-task-001", "--tasks-root", tasksRoot.toString());
        assertThat(initExit).isZero();

        Path taskDir = tasksRoot.resolve("demo-task-001");
        assertThat(taskDir.resolve("task.yaml")).isRegularFile();
        assertThat(taskDir.resolve("work/notes.md")).isRegularFile();
        assertThat(taskDir.resolve("hidden/judge.rules.yaml")).isRegularFile();
        assertThat(taskDir.resolve("hidden/expected/answer.json")).isRegularFile();
        assertThat(taskDir.resolve("samples/replay.yaml")).isRegularFile();
        assertThat(taskDir.resolve("samples/attempt-pass.json")).isRegularFile();
        assertThat(taskDir.resolve("samples/attempt-fail.json")).isRegularFile();

        // 静态体检（含规则深度 lint）通过。
        int validateExit = new CommandLine(new Main()).execute(
                "validate", "--task", taskDir.toString());
        assertThat(validateExit).isZero();

        // 回放闭环：第 1 轮失败 → 按反馈修正 → 第 2 轮满分通过。
        RunManager.RunOutcome outcome = RunManager.run(taskDir, root.resolve("runs"), null,
                new ScriptedAgentAdapter(taskDir.resolve("samples/replay.yaml")));
        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_002");
        assertThat(outcome.bestScore()).isEqualTo(100.0);

        JsonNode report = Jsons.json().readTree(Files.readString(outcome.reportJson()));
        assertThat(report.path("score_trajectory").get(0).asDouble()).isLessThan(80);

        // 首轮反馈不泄露隐藏期望值（7080 只存在于 hidden/expected）。
        String feedback = Files.readString(
                outcome.runDir().resolve("feedback/attempt_001.feedback.json"));
        assertThat(feedback).doesNotContain("7080");
    }

    @Test
    void 目录已存在_拒绝覆盖() throws Exception {
        Path tasksRoot = root.resolve("tasks");
        Files.createDirectories(tasksRoot.resolve("dup-task-001"));

        int exit = new CommandLine(new Main()).execute(
                "task", "init", "--id", "dup-task-001", "--tasks-root", tasksRoot.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(tasksRoot.resolve("dup-task-001/task.yaml")).doesNotExist();
    }

    @Test
    void 非法任务id_拒绝生成() {
        Path tasksRoot = root.resolve("tasks");
        for (String badId : new String[] {"BadCase-001", "no-suffix", "has_underscore-001", "-lead-001"}) {
            int exit = new CommandLine(new Main()).execute(
                    "task", "init", "--id", badId, "--tasks-root", tasksRoot.toString());
            assertThat(exit).as("id %s 应被拒绝", badId).isEqualTo(1);
        }
        assertThat(tasksRoot).doesNotExist();
    }
}
