package com.agenteval.judge;

import com.agenteval.util.Dirs;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 脚本评审：把评分逻辑外包给任务自带脚本（python/bash/任意可执行），
 * 覆盖规则引擎表达不了的领域判断。
 *
 * <p>脚本契约（stdout 必须输出 JSON）：
 * <pre>{@code
 * {
 *   "checks": [
 *     {"id": "X", "dimension": "correctness", "points_earned": 8, "points_possible": 10,
 *      "passed": true, "blocking": false, "severity": "medium",
 *      "message": "内部诊断", "external_message": "对外文案"}
 *   ],
 *   "private_notes": "可选"
 * }
 * }</pre>
 *
 * <p>输入经环境变量传递（AEL_SUBMISSION / AEL_WORKSPACE / AEL_HIDDEN / AEL_TRACE /
 * AEL_TASK_YAML / AEL_RUN_ID / AEL_ATTEMPT_ID）。脚本同样在 workspace 临时副本上运行，
 * 且超时/崩溃/输出不合契约一律按评审设施故障抛 {@link JudgeException}。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class ScriptJudge {

    private ScriptJudge() {
    }

    /**
     * 执行评分脚本并解析 check 结论。
     *
     * @param input 评审输入
     * @param dimensionNames 合法维度名（脚本返回的 dimension 必须落在其中）
     * @return 脚本产出的检查结论
     * @throws JudgeException 脚本失败或输出不合契约时
     */
    public static List<CheckOutcome> run(JudgeInput input, Set<String> dimensionNames) {
        String scriptRel = input.taskSpec().judge().script();
        Path script = input.taskDir().resolve(scriptRel);
        if (!Files.isRegularFile(script)) {
            throw new JudgeException("评分脚本不存在: " + script);
        }
        Path ephemeral;
        try {
            ephemeral = Files.createTempDirectory("ael-script-judge-");
            Dirs.copyTree(input.workspaceDir(), ephemeral);
        } catch (IOException e) {
            throw new JudgeException("创建脚本评审临时区失败", e);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", script.toAbsolutePath().toString())
                    .directory(ephemeral.toFile())
                    .redirectErrorStream(false);
            builder.environment().put("AEL_SUBMISSION", input.submissionFile().toAbsolutePath().toString());
            builder.environment().put("AEL_WORKSPACE", ephemeral.toAbsolutePath().toString());
            builder.environment().put("AEL_HIDDEN", input.hiddenDir().toAbsolutePath().toString());
            builder.environment().put("AEL_TRACE",
                    input.traceFile() == null ? "" : input.traceFile().toAbsolutePath().toString());
            builder.environment().put("AEL_TASK_YAML", input.taskDir().resolve("task.yaml").toAbsolutePath().toString());
            builder.environment().put("AEL_RUN_ID", input.runId());
            builder.environment().put("AEL_ATTEMPT_ID", input.attemptId());

            int timeout = input.taskSpec().judge().scriptTimeoutSeconds();
            Process process = builder.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new JudgeException("评分脚本超时（" + timeout + "s）: " + scriptRel);
            }
            if (process.exitValue() != 0) {
                throw new JudgeException("评分脚本退出码 " + process.exitValue() + ": "
                        + new String(stderr, StandardCharsets.UTF_8));
            }
            return parseChecks(new String(stdout, StandardCharsets.UTF_8), dimensionNames, scriptRel);
        } catch (IOException e) {
            throw new JudgeException("评分脚本执行失败: " + scriptRel, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgeException("评分脚本被中断: " + scriptRel, e);
        } finally {
            Dirs.deleteTree(ephemeral);
        }
    }

    private static List<CheckOutcome> parseChecks(String stdout, Set<String> dimensionNames, String scriptRel) {
        JsonNode root;
        try {
            root = Jsons.json().readTree(stdout);
        } catch (IOException e) {
            throw new JudgeException("评分脚本 stdout 不是合法 JSON: " + scriptRel, e);
        }
        JsonNode checks = root.path("checks");
        if (!checks.isArray() || checks.isEmpty()) {
            throw new JudgeException("评分脚本未返回 checks 数组: " + scriptRel);
        }
        List<CheckOutcome> outcomes = new ArrayList<>();
        for (JsonNode node : checks) {
            String id = node.path("id").asText("");
            String dimension = node.path("dimension").asText("");
            if (id.isBlank() || !dimensionNames.contains(dimension)) {
                throw new JudgeException("脚本 check 非法（id 缺失或 dimension 未声明）: " + node);
            }
            outcomes.add(new CheckOutcome(
                    id,
                    dimension,
                    node.path("points_earned").asDouble(0),
                    node.path("points_possible").asDouble(0),
                    node.path("passed").asBoolean(false),
                    node.path("blocking").asBoolean(false),
                    node.path("severity").asText("medium"),
                    node.path("message").asText(""),
                    node.path("external_message").asText("")));
        }
        return outcomes;
    }
}
