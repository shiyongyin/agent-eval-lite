package com.agenteval.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval task}：任务库工程化辅助（当前提供 {@code init} 脚手架）。
 *
 * <p>{@code task init} 生成一个<strong>开箱即可运行</strong>的任务骨架：产物直接通过
 * {@code validate} 静态体检，且自带「第 1 轮失败 → 按反馈修正 → 第 2 轮通过」的
 * scripted 回放闭环——任务作者从一个已经能跑的最小任务开始改，而不是从空目录开始猜。
 *
 * <p>模板刻意演示两类最常用的判分形态：{@code jsonpath_equals}（经 {@code expected_from}
 * 引用隐藏期望值的精确比对）与 {@code list_coverage}（关键点覆盖的部分得分）。
 * 全部 14 种 check 类型见 README「写一个新任务」。
 *
 * @author shiyongyin
 * @since 0.3.0
 */
@Command(name = "task", mixinStandardHelpOptions = true, description = "任务库工程化辅助",
        subcommands = {TaskCommand.InitCommand.class})
public final class TaskCommand {

    /**
     * {@code task init} 子命令：生成新任务脚手架。
     */
    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "生成新任务脚手架（开箱即过 validate 与回放闭环）")
    public static final class InitCommand implements Callable<Integer> {

        /** 任务 id 约定：kebab-case + 三位数字后缀（与 AGENTS.md 一致，如 code-fix-001）。 */
        private static final Pattern ID_PATTERN =
                Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*-[0-9]{3}$");

        @Option(names = "--id", required = true,
                description = "任务 id（kebab-case + 三位数字后缀，如 my-task-001；同时作为目录名）")
        private String taskId;

        @Option(names = "--tasks-root", defaultValue = "tasks",
                description = "任务库根目录（默认 ${DEFAULT-VALUE}）")
        private Path tasksRoot;

        @Override
        public Integer call() {
            if (!ID_PATTERN.matcher(taskId).matches()) {
                System.err.println("错误: 任务 id 需为 kebab-case + 三位数字后缀（如 my-task-001），实际: " + taskId);
                return 1;
            }
            Path taskDir = tasksRoot.resolve(taskId);
            if (Files.exists(taskDir)) {
                System.err.println("错误: 目录已存在，拒绝覆盖: " + taskDir);
                return 1;
            }

            try {
                for (Map.Entry<String, String> entry : templates().entrySet()) {
                    Path file = taskDir.resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.getValue().replace("__TASK_ID__", taskId),
                            StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                System.err.println("错误: 写入脚手架失败: " + e.getMessage());
                return 2;
            }

            System.out.println("已生成任务脚手架: " + taskDir);
            System.out.println("  ├── task.yaml                    # 任务规格（改 agent_brief / 维度 / 通过线）");
            System.out.println("  ├── work/notes.md                # Agent 可见材料（换成你的真实材料）");
            System.out.println("  ├── hidden/judge.rules.yaml      # 隐藏评审规则（feedback_fail 禁含期望值）");
            System.out.println("  ├── hidden/expected/answer.json  # 隐藏期望值（经 expected_from 引用）");
            System.out.println("  └── samples/                     # 失败/通过样例 + 回放脚本（任务自测）");
            System.out.println();
            System.out.println("下一步:");
            System.out.println("  1. bin/agent-eval validate --task " + taskDir);
            System.out.println("  2. bin/agent-eval run --task " + taskDir
                    + " --agent scripted --script " + taskDir.resolve("samples/replay.yaml"));
            System.out.println("  3. 用真实材料替换模板内容后重跑 1、2（保持 fail→pass 闭环可复现）");
            return 0;
        }

        /**
         * 脚手架文件清单（相对任务目录 → 内容；{@code __TASK_ID__} 会被替换，
         * {@code {attempt_id}} 是回放期占位符原样保留）。
         *
         * @return 有序文件模板表
         */
        private static Map<String, String> templates() {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("task.yaml", """
                    schema_version: 1
                    task_id: __TASK_ID__
                    task_name: TODO 一句话任务名（示例：从部署备忘中提取服务端口）
                    task_type: generic
                    # 分层（可选，供 suite --tier 过滤；不影响判分）：smoke / regression / security / domain
                    tier: regression
                    labels: []
                    description: >
                      TODO（评估者视角，不渲染给 Agent）：这个任务评什么能力、为什么值得评。
                      模板演示「读材料 → 提取事实 → 结构化提交 + 注明出处」的最小闭环。

                    agent_brief: |
                      阅读 `notes.md`，找出「服务默认监听端口」。

                      - 把端口号作为**字符串**填入提交的 `answer.value`；
                      - 在 `summary` 里说明结论出处（引用材料文件名）。

                    visible_context:
                      - work/notes.md

                    allowed_tools: []

                    submit:
                      format: json
                      schema: builtin:generic
                      max_attempts: 3
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
                          weight: 70
                        - name: explanation
                          weight: 30

                    runtime:
                      timeout_minutes: 20
                      attempt_timeout_minutes: 5
                      allow_multi_submit: true
                      auto_eval_interval_seconds: 0
                      resume_enabled: true
                    """);
            files.put("work/notes.md", """
                    # 部署备忘（模板示例材料，请替换为你的真实任务材料）

                    - 服务使用 systemd 托管，进程名 `demo-svc`。
                    - 服务默认监听端口是 7080，可用环境变量 `DEMO_PORT` 覆盖。
                    - 日志输出到 `/var/log/demo-svc/`，按天滚动保留 14 天。
                    """);
            files.put("hidden/judge.rules.yaml", """
                    # __TASK_ID__ 隐藏评审规则（Agent 不可见）。
                    # 模板演示两类最常用 check：精确比对（expected_from 引用隐藏期望值）与关键点覆盖（部分得分）。
                    # 注意：feedback_fail 是回传 Agent 的对外文案，禁止包含期望值本身。
                    schema_version: 1
                    judge_version: "0.1.0"

                    checks:
                      - id: ANSWER_CORRECT
                        type: jsonpath_equals
                        dimension: correctness
                        points: 70
                        severity: critical
                        path: "$.answer.value"
                        expected_from: "expected/answer.json#/value"
                        feedback_fail: answer.value 不正确，请重读材料中与「端口」相关的内容

                      - id: SOURCE_CITED
                        type: list_coverage
                        dimension: explanation
                        points: 30
                        severity: low
                        path: "$.summary"
                        min_matches: 1
                        expected_any_of:
                          - ["notes.md", "备忘"]
                        feedback_fail: summary 未说明结论出处（应引用材料文件名）
                    """);
            files.put("hidden/expected/answer.json", """
                    {
                      "value": "7080"
                    }
                    """);
            files.put("samples/attempt-pass.json", """
                    {
                      "schema_version": 1,
                      "task_id": "__TASK_ID__",
                      "attempt_id": "{attempt_id}",
                      "submission_type": "generic",
                      "summary": "依据 notes.md 部署备忘：服务默认监听端口为 7080（可被 DEMO_PORT 覆盖）",
                      "answer": { "value": "7080" },
                      "known_risks": [],
                      "needs_human_review": false
                    }
                    """);
            files.put("samples/attempt-fail.json", """
                    {
                      "schema_version": 1,
                      "task_id": "__TASK_ID__",
                      "attempt_id": "{attempt_id}",
                      "submission_type": "generic",
                      "summary": "按部署备忘推测服务端口是 8080（示例错误提交：未核对材料）",
                      "answer": { "value": "8080" },
                      "known_risks": [],
                      "needs_human_review": false
                    }
                    """);
            files.put("samples/replay.yaml", """
                    # 回放脚本：第 1 轮答错（未核对材料），按反馈修正后第 2 轮通过。
                    attempts:
                      - submission_file: samples/attempt-fail.json
                      - submission_file: samples/attempt-pass.json
                    """);
            return files;
        }
    }
}
