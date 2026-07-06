package com.agenteval.runner;

import com.agenteval.judge.JudgeInput;
import com.agenteval.judge.JudgeResult;
import com.agenteval.judge.JudgeRunner;
import com.agenteval.task.TaskContext;
import com.agenteval.trace.TraceEventType;
import com.agenteval.trace.TraceLogger;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * auto-eval 后台采样器（Phase 3，设计 §10）：agent attempt 执行期间按
 * {@code runtime.auto_eval_interval_seconds} 间隔快照当前 workspace 并跑一次隐藏评审，
 * 产出「进行中的得分轨迹」。
 *
 * <p>硬性语义边界：
 * <ul>
 *   <li><strong>结果不回注 Agent</strong>——不写 feedback、不进 run_state、不影响轮次判定与
 *       最终成绩，只落 trace 事件（{@code auto_eval_sampled}）供报告/观测侧还原轨迹；</li>
 *   <li>采样评审在 workspace 的<strong>临时副本</strong>上执行（{@code RulesJudge} 固有行为），
 *       永不污染 Agent 工作区；产物落 {@code judge/auto/}（Agent 禁区）；</li>
 *   <li>提交视角：本轮 inbox 提交已出现则对其评审（草稿也算），否则以空提交 {@code {}}
 *       评审——此时只有 workspace 类检查有信号，正是「代码任务进度可观测」的目标形态；</li>
 *   <li>采样失败（agent 并发写文件导致快照抖动等）只记录 error 样本，绝不影响 run 主流程；</li>
 *   <li>单线程 fixed-delay：上一次采样未结束不会叠加下一次（command 型检查可能秒级耗时）。</li>
 * </ul>
 *
 * @author shiyongyin
 * @since 0.4.0
 */
public final class AutoEvalSampler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AutoEvalSampler.class);

    private final TaskContext ctx;
    private final TraceLogger trace;
    private final String runId;
    private final String attemptId;
    private final byte[] traceSecret;
    private final Duration interval;
    private final Thread worker;
    private volatile boolean closed;
    private int sampleSeq;

    private AutoEvalSampler(TaskContext ctx, TraceLogger trace, String runId,
                            String attemptId, byte[] traceSecret, Duration interval) {
        this.ctx = ctx;
        this.trace = trace;
        this.runId = runId;
        this.attemptId = attemptId;
        this.traceSecret = traceSecret;
        this.interval = interval;
        this.worker = new Thread(this::loop, "auto-eval-" + attemptId);
        this.worker.setDaemon(true);
    }

    /**
     * 为一个 attempt 启动采样（间隔 ≤0 时返回空操作实例，零开销）。
     *
     * @param ctx run 上下文
     * @param trace 签名 trace（采样事件由框架进程代写，天然可信）
     * @param runId run id
     * @param attemptId 当前 attempt id
     * @param traceSecret trace 签名密钥（供评审内的工具轨迹核验）
     * @return 采样器（须在 attempt 结束后 {@link #close()}）
     */
    public static AutoEvalSampler start(TaskContext ctx, TraceLogger trace, String runId,
                                        String attemptId, byte[] traceSecret) {
        int seconds = ctx.spec().runtime().autoEvalIntervalSeconds();
        AutoEvalSampler sampler = new AutoEvalSampler(
                ctx, trace, runId, attemptId, traceSecret, Duration.ofSeconds(Math.max(seconds, 0)));
        if (seconds > 0) {
            sampler.worker.start();
        } else {
            sampler.closed = true;
        }
        return sampler;
    }

    private void loop() {
        while (!closed) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) {
                return;
            }
            sampleOnce();
        }
    }

    private void sampleOnce() {
        sampleSeq++;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sample_seq", sampleSeq);
        payload.put("kind", "auto");
        try {
            Path submissionFile = currentSubmissionFile();
            boolean hasSubmission = Files.isRegularFile(ctx.inboxDir().resolve(attemptId + ".json"));
            JsonNode submission = readSubmission(submissionFile);
            Path sampleDir = ctx.judgeDir().resolve("auto")
                    .resolve(attemptId + "-sample-" + String.format("%03d", sampleSeq));
            JudgeResult result = JudgeRunner.judge(new JudgeInput(
                    ctx.spec(), ctx.taskDir(), submission, submissionFile,
                    ctx.workspaceDir(), ctx.baselineFile(), ctx.traceFile(),
                    sampleDir, runId, attemptId, traceSecret));
            payload.put("score", result.score());
            payload.put("passed", result.passed());
            payload.put("failed_checks", result.failedRules().size());
            payload.put("has_submission", hasSubmission);
        } catch (RuntimeException e) {
            // agent 正在写 workspace 时快照可能抖动——按失败样本留痕即可，绝不影响主流程。
            payload.put("error", String.valueOf(e.getMessage()));
            log.debug("auto-eval 采样失败（忽略）: {}", e.getMessage());
        }
        if (!closed) {
            trace.log(TraceEventType.AUTO_EVAL_SAMPLED, attemptId, payload);
        }
    }

    /**
     * 采样使用的提交文件：本轮 inbox 已有提交（含草稿）则用它；否则用空提交占位文件
     * （只让 workspace 类检查产生信号）。
     */
    private Path currentSubmissionFile() {
        Path inboxFile = ctx.inboxDir().resolve(attemptId + ".json");
        if (Files.isRegularFile(inboxFile)) {
            return inboxFile;
        }
        Path placeholder = ctx.judgeDir().resolve("auto").resolve("empty-submission.json");
        if (!Files.isRegularFile(placeholder)) {
            try {
                Files.createDirectories(placeholder.getParent());
                Files.writeString(placeholder, "{}", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("写入 auto-eval 占位提交失败", e);
            }
        }
        return placeholder;
    }

    private static JsonNode readSubmission(Path file) {
        try {
            return Jsons.json().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 提交文件正被 agent 写到一半（非法 JSON / 读失败）：按空提交采样。
            return Jsons.json().createObjectNode();
        }
    }

    /** 停止采样（等待在跑的样本让位，最多 2 秒；之后线程作为 daemon 被放弃）。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(2).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
