package com.agenteval.cli;

import com.agenteval.judge.JudgeInput;
import com.agenteval.judge.JudgeResult;
import com.agenteval.judge.JudgeRunner;
import com.agenteval.submission.SubmissionManager;
import com.agenteval.submission.SubmissionValidationResult;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import com.agenteval.trace.TraceSecret;
import com.agenteval.util.Dirs;
import com.agenteval.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval judge}：离线判分——不跑 Agent，直接对一份提交复算分数。
 *
 * <p>这是「评分可复现」承诺的兑现工具：任何人拿任务目录 + 提交文件即可复核历史分数。
 * 默认在任务 work/ 的临时副本上判分；如需针对某次 run 的真实工作区复算，
 * 用 {@code --workspace <runs/.../workspace>} 与 {@code --trace <runs/.../traces/trace.jsonl>} 指定。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "judge", mixinStandardHelpOptions = true, description = "对一份提交离线判分（可复现复核）")
public final class JudgeCommand implements Callable<Integer> {

    @Option(names = "--task", required = true, description = "任务目录")
    private Path taskDir;

    @Option(names = "--submission", required = true, description = "提交 JSON 文件")
    private Path submission;

    @Option(names = "--workspace", description = "判分依据的工作区（默认: 任务 work/ 的临时副本）")
    private Path workspace;

    @Option(names = "--trace", description = "trace.jsonl（工具轨迹类检查需要）")
    private Path trace;

    @Option(names = "--out", description = "评分结果输出文件（默认打印 stdout）")
    private Path out;

    @Override
    public Integer call() throws Exception {
        TaskSpec spec = TaskSpecLoader.load(taskDir);
        SubmissionValidationResult validation =
                SubmissionManager.validate(submission, spec, taskDir, null);
        if (!validation.valid()) {
            System.err.println("提交未通过 schema 校验:");
            validation.errors().forEach(error -> System.err.println("  - " + error));
            return 3;
        }

        Path effectiveWorkspace = workspace;
        Path tempWorkspace = null;
        if (effectiveWorkspace == null) {
            tempWorkspace = Files.createTempDirectory("ael-offline-judge-");
            Dirs.copyTree(taskDir.resolve("work"), tempWorkspace);
            effectiveWorkspace = tempWorkspace;
        }
        try {
            // 若 --trace 指向某次 run 的 traces/trace.jsonl，则加载该 run 收尾时落盘的签名密钥，
            // 使离线复核同样只认可核验签名的 tool_call 事件（与 run 内判分口径一致）。
            byte[] traceSecret = loadTraceSecret(trace);
            JudgeResult result = JudgeRunner.judge(new JudgeInput(
                    spec, taskDir.toAbsolutePath().normalize(),
                    validation.submission(), submission,
                    effectiveWorkspace, null, trace, null,
                    "offline", submission.getFileName().toString().replace(".json", ""),
                    traceSecret));
            String json = Jsons.json().writeValueAsString(result);
            if (out != null) {
                Files.writeString(out, json, StandardCharsets.UTF_8);
                System.out.println("评分结果已写入: " + out);
            } else {
                System.out.println(json);
            }
            return 0;
        } finally {
            if (tempWorkspace != null) {
                Dirs.deleteTree(tempWorkspace);
            }
        }
    }

    private static byte[] loadTraceSecret(Path traceFile) {
        if (traceFile == null || traceFile.getParent() == null) {
            return null;
        }
        Path tracesDir = traceFile.getParent();
        if (!"traces".equals(tracesDir.getFileName().toString()) || tracesDir.getParent() == null) {
            return null;
        }
        return TraceSecret.load(tracesDir.getParent());
    }
}
