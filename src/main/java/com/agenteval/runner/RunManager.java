package com.agenteval.runner;

import com.agenteval.Version;
import com.agenteval.agent.AgentAdapter;
import com.agenteval.agent.AttemptInput;
import com.agenteval.agent.AttemptOutcome;
import com.agenteval.judge.FeedbackPolicy;
import com.agenteval.judge.JudgeInput;
import com.agenteval.judge.JudgeResult;
import com.agenteval.judge.JudgeRunner;
import com.agenteval.report.BestAttemptSelector;
import com.agenteval.report.ReportGenerator;
import com.agenteval.state.RunMeta;
import com.agenteval.state.RunState;
import com.agenteval.state.RunStateStore;
import com.agenteval.state.RunStatus;
import com.agenteval.submission.SubmissionManager;
import com.agenteval.submission.SubmissionValidationResult;
import com.agenteval.task.TaskContext;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import com.agenteval.task.InstructionsRenderer;
import com.agenteval.tool.ToolAccess;
import com.agenteval.tool.ToolGateway;
import com.agenteval.tool.ToolGatewayServer;
import com.agenteval.trace.TraceEventType;
import com.agenteval.trace.TraceLogger;
import com.agenteval.trace.TraceSecret;
import com.agenteval.util.Hashes;
import com.agenteval.util.Ids;
import com.agenteval.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runner：一次评估 run 的全生命周期编排。
 *
 * <p>执行循环（设计 §8 时序图的代码化）：
 * <pre>
 * 初始化(目录/指纹/说明) → [attempt 循环: agent 执行 → 收件 → schema 校验 →
 * 隐藏评审 → 受控反馈 → 快照] → 完整性复核 → 最佳 attempt 选择 → 报告
 * </pre>
 *
 * <p>循环退出条件（先到先停）：通过且策略允许提前停、次数用尽、run 超时、
 * 适配器耗尽、Agent 声明需人工复核、评审设施故障。轻量 stop-hook 的体现：
 * Agent「自称完成」不是退出条件——没有通过评审之前，只要还有剩余轮次就带着反馈继续。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    private RunManager() {
    }

    /**
     * run 配置。
     *
     * @param taskDir 任务目录（新 run 必填）
     * @param runsRoot runs 根目录（默认 {@code runs}）
     * @param modelName 模型标识（报告用，可为空）
     * @param adapter agent 适配器
     * @param resumeRunDir 待恢复的 run 目录（非 {@code null} 即 resume 模式）
     */
    public record RunConfig(Path taskDir, Path runsRoot, String modelName,
                            AgentAdapter adapter, Path resumeRunDir) {
    }

    /**
     * run 结论摘要（CLI 输出用）。
     *
     * @param runId run id
     * @param runDir run 目录
     * @param status 终态
     * @param bestScore 最佳分数（无有效提交时为 {@code null}）
     * @param bestAttemptId 最佳 attempt id（可为 {@code null}）
     * @param reportJson report.json 路径
     * @param reportMd report.md 路径
     */
    public record RunOutcome(String runId, Path runDir, RunStatus status,
                             Double bestScore, String bestAttemptId,
                             Path reportJson, Path reportMd) {
    }

    /**
     * 执行（或恢复）一次评估 run。
     *
     * @param config run 配置
     * @return run 结论摘要
     */
    public static RunOutcome execute(RunConfig config) {
        boolean resuming = config.resumeRunDir() != null;
        TaskContext ctx;
        RunState state;
        TraceLogger trace;
        byte[] traceSecret;

        if (resuming) {
            RunMeta meta = RunMeta.load(config.resumeRunDir().resolve("meta.json"));
            Path taskDir = Path.of(meta.taskDir());
            TaskSpec spec = TaskSpecLoader.load(taskDir);
            ctx = TaskContext.of(spec, taskDir, config.resumeRunDir());
            state = RunStateStore.load(ctx.runStateFile());
            if (state == null) {
                throw new IllegalStateException("run_state.json 不存在，无法恢复: " + config.resumeRunDir());
            }
            if (!spec.runtime().resumeEnabled()) {
                throw new IllegalStateException("任务未启用 resume: " + spec.taskId());
            }
            traceSecret = TraceSecret.obtain(ctx.runDir());
            trace = TraceLogger.open(ctx.traceFile(), state.runId(), traceSecret);
            // 完整性前置校验：恢复前 hidden 必须与首跑一致，否则整个评估作废。
            String hiddenNow = Hashes.sha256OfDir(ctx.hiddenDir());
            if (!hiddenNow.equals(state.hiddenFingerprintBefore())) {
                trace.log(TraceEventType.ERROR, null, Map.of(
                        "reason", "hidden_fingerprint_mismatch_on_resume",
                        "expected", state.hiddenFingerprintBefore(), "actual", hiddenNow));
                state = state.finish(RunStatus.INTEGRITY_BROKEN, "hidden 目录在恢复前被修改", null);
                RunStateStore.save(ctx.runStateFile(), state);
                return finalize(ctx, state, trace, null, traceSecret);
            }
            trace.log(TraceEventType.RESUME, null, Map.of(
                    "completed_attempts", state.attempts().size(),
                    "next_attempt", state.nextAttemptNumber()));
            log.info("恢复 run {}（已完成 {} 轮）", state.runId(), state.attempts().size());
        } else {
            TaskSpec spec = TaskSpecLoader.load(config.taskDir());
            String runId = Ids.newRunId();
            Path runDir = config.runsRoot().resolve(spec.taskId()).resolve(runId);
            ctx = TaskContext.of(spec, config.taskDir(), runDir);

            WorkspaceManager.Fingerprints fingerprints = WorkspaceManager.prepare(ctx);
            writeInstructions(ctx);
            new RunMeta(runId, spec.taskId(), ctx.taskDir().toString(),
                    config.adapter().name(),
                    config.modelName() == null ? "" : config.modelName(),
                    Version.ENGINE, Instant.now())
                    .save(ctx.metaFile());

            state = new RunState(1, runId, spec.taskId(), RunStatus.RUNNING, null,
                    Instant.now(), Instant.now(), java.util.List.of(), null,
                    fingerprints.hiddenFingerprint(), fingerprints.workspaceFingerprint());
            RunStateStore.save(ctx.runStateFile(), state);

            traceSecret = TraceSecret.obtain(ctx.runDir());
            trace = TraceLogger.open(ctx.traceFile(), runId, traceSecret);
            trace.log(TraceEventType.RUN_STARTED, null, Map.of(
                    "task_id", spec.taskId(),
                    "agent", config.adapter().name(),
                    "model", config.modelName() == null ? "" : config.modelName(),
                    "engine_version", Version.ENGINE,
                    "hidden_fingerprint", fingerprints.hiddenFingerprint(),
                    "workspace_fingerprint", fingerprints.workspaceFingerprint()));
            log.info("run {} 启动，任务 {}，agent {}", runId, spec.taskId(), config.adapter().name());
        }

        // 常驻工具网关：Agent（及进程内适配器）经此调用工具，由服务端用本 run 密钥代写签名事件。
        ToolGateway gateway = ToolGateway.withSigningTrace(
                ctx.runDir(), ctx.taskDir(), ctx.spec(), trace);
        ToolGatewayServer toolServer = ToolGatewayServer.start(gateway);
        ToolAccess toolAccess = new ToolAccess(gateway, toolServer.endpoint(), toolServer.token());
        try {
            return runLoop(ctx, state, trace, config.adapter(), toolAccess, traceSecret);
        } finally {
            toolServer.close();
            // Agent 已停止后才落盘密钥：供离线复核与后续 resume 复用（详见 TraceSecret 的安全不变量）。
            TraceSecret.save(ctx.runDir(), traceSecret);
        }
    }

    // ---------------------------------------------------------------- loop

    private static RunOutcome runLoop(TaskContext ctx, RunState state, TraceLogger trace,
                                      AgentAdapter adapter, ToolAccess toolAccess, byte[] traceSecret) {
        TaskSpec spec = ctx.spec();
        Instant loopStart = Instant.now();
        Duration runBudget = Duration.ofMinutes(spec.runtime().timeoutMinutes());
        String terminalReason = null;
        RunStatus terminalStatus = null;

        if (spec.runtime().autoEvalIntervalSeconds() > 0) {
            log.info("auto-eval 后台采样已启用（间隔 {}s，结果只进 trace/report 轨迹，不回注 Agent）",
                    spec.runtime().autoEvalIntervalSeconds());
        }

        while (state.nextAttemptNumber() <= spec.submit().maxAttempts()) {
            if (Duration.between(loopStart, Instant.now()).compareTo(runBudget) > 0) {
                terminalStatus = RunStatus.FAILED;
                terminalReason = "timeout";
                break;
            }
            int attemptNumber = state.nextAttemptNumber();
            String attemptId = Ids.attemptId(attemptNumber);
            Path previousFeedback = attemptNumber == 1 ? null
                    : ctx.feedbackDir().resolve(Ids.attemptId(attemptNumber - 1) + ".feedback.json");
            if (previousFeedback != null && !Files.isRegularFile(previousFeedback)) {
                previousFeedback = null;
            }

            AttemptInput input = new AttemptInput(ctx, attemptId, attemptNumber,
                    ctx.instructionsFile(), previousFeedback,
                    Duration.ofMinutes(spec.runtime().attemptTimeoutMinutes()), toolAccess);

            trace.log(TraceEventType.AGENT_STARTED, attemptId, Map.of(
                    "adapter", adapter.name(), "attempt_number", attemptNumber));
            long attemptStart = System.nanoTime();
            AttemptOutcome outcome;
            // auto-eval 采样只在 agent 执行窗口内活跃（间隔为 0 时是零开销空操作）。
            try (AutoEvalSampler sampler = AutoEvalSampler.start(
                    ctx, trace, state.runId(), attemptId, traceSecret)) {
                outcome = adapter.runAttempt(input);
            }
            trace.log(TraceEventType.AGENT_FINISHED, attemptId, Map.of(
                    "exit_code", outcome.exitCode(),
                    "declared_done", outcome.agentDeclaredDone(),
                    "has_submission", outcome.submissionFile() != null,
                    "log_file", outcome.agentLogFile() == null ? "" : outcome.agentLogFile().toString()));

            if (outcome.submissionFile() == null) {
                if (outcome.exhausted()) {
                    terminalStatus = RunStatus.FAILED;
                    terminalReason = "agent_exhausted";
                    break;
                }
                // 有轮次但没交卷：按无效轮记录，反馈提醒后继续（不给 Agent 白嫖轮次的机会）。
                trace.log(TraceEventType.ERROR, attemptId, Map.of("reason", "no_submission"));
                FeedbackPolicy.writeInvalid(ctx.feedbackDir(), attemptId,
                        java.util.List.of("本轮未在 inbox 中发现提交文件 " + attemptId + ".json"),
                        nextAttemptId(spec, attemptNumber));
                state = state.withAttempt(new RunState.AttemptRecord(
                        attemptId, false, null, false, 0, java.util.List.of(), false,
                        elapsedMs(attemptStart), Instant.now()));
                RunStateStore.save(ctx.runStateFile(), state);
                continue;
            }

            trace.log(TraceEventType.SUBMISSION_RECEIVED, attemptId, Map.of(
                    "file", outcome.submissionFile().toString(),
                    "sha256", Hashes.sha256OfFile(outcome.submissionFile())));

            SubmissionValidationResult validation = SubmissionManager.validate(
                    outcome.submissionFile(), spec, ctx.taskDir(), attemptId);
            if (!validation.valid()) {
                trace.log(TraceEventType.SUBMISSION_INVALID, attemptId, Map.of(
                        "errors", validation.errors()));
                Path feedbackFile = FeedbackPolicy.writeInvalid(ctx.feedbackDir(), attemptId,
                        validation.errors(), nextAttemptId(spec, attemptNumber));
                trace.log(TraceEventType.FEEDBACK_DELIVERED, attemptId,
                        Map.of("file", feedbackFile.toString(), "valid", false));
                state = state.withAttempt(new RunState.AttemptRecord(
                        attemptId, false, null, false, 0, java.util.List.of(), false,
                        elapsedMs(attemptStart), Instant.now()));
                RunStateStore.save(ctx.runStateFile(), state);
                cooldown(spec, attemptNumber);
                continue;
            }

            // Agent 自报的 token/成本消耗（可选）：留痕进 trace 供报告/套件聚合。
            // 自报口径、不参与评分——只作 ROI 对比的参考列（tau-bench 式成本视角）。
            JsonNode usage = validation.submission().path("usage");
            if (usage.isObject() && !usage.isEmpty()) {
                Map<String, Object> usagePayload = new LinkedHashMap<>();
                usage.properties().forEach(entry -> usagePayload.put(entry.getKey(), entry.getValue()));
                usagePayload.put("source", "agent_self_report");
                trace.log(TraceEventType.USAGE_RECORDED, attemptId, usagePayload);
            }

            trace.log(TraceEventType.JUDGE_STARTED, attemptId, Map.of(
                    "judge_type", spec.judge().type().jsonName()));
            JudgeResult result;
            try {
                result = JudgeRunner.judge(new JudgeInput(spec, ctx.taskDir(),
                        validation.submission(), outcome.submissionFile(),
                        ctx.workspaceDir(), ctx.baselineFile(), ctx.traceFile(),
                        ctx.judgeDir(), state.runId(), attemptId, traceSecret));
            } catch (RuntimeException e) {
                // 评审设施故障 ≠ Agent 低分：run 以 ERROR 终止，不产生任何分数。
                log.error("评审设施故障", e);
                trace.log(TraceEventType.ERROR, attemptId, Map.of(
                        "reason", "judge_failure", "message", String.valueOf(e.getMessage())));
                state = state.finish(RunStatus.ERROR, "judge_failure: " + e.getMessage(), null);
                RunStateStore.save(ctx.runStateFile(), state);
                return finalize(ctx, state, trace, null, traceSecret);
            }
            writeJudgeResult(ctx, attemptId, result);
            trace.log(TraceEventType.JUDGE_COMPLETED, attemptId, Map.of(
                    "score", result.score(), "passed", result.passed(),
                    "failed_rules", result.failedRules().size(),
                    "judge_rules_fingerprint", result.reproducibility().judgeRulesFingerprint()));

            boolean needsHuman = validation.submission().path("needs_human_review").asBoolean(false);
            Path feedbackFile = FeedbackPolicy.writeJudged(ctx.feedbackDir(), spec, result,
                    result.passed() ? null : nextAttemptId(spec, attemptNumber));
            trace.log(TraceEventType.FEEDBACK_DELIVERED, attemptId, Map.of(
                    "file", feedbackFile.toString(), "level", spec.judge().feedback().level().jsonName()));

            int blockingFailures = (int) result.failedRules().stream()
                    .filter(JudgeResult.FailedRule::blocking).count();
            state = state.withAttempt(new RunState.AttemptRecord(
                    attemptId, true, result.score(), result.passed(), blockingFailures,
                    result.failedRules().stream().map(JudgeResult.FailedRule::ruleId).toList(),
                    needsHuman, elapsedMs(attemptStart), Instant.now()));
            RunStateStore.save(ctx.runStateFile(), state);

            if (needsHuman) {
                terminalStatus = RunStatus.PENDING_HUMAN;
                terminalReason = "agent_requested_human_review";
                break;
            }
            if (result.passed()) {
                terminalStatus = RunStatus.PASSED;
                terminalReason = "passed";
                break;
            }
            if (!spec.runtime().allowMultiSubmit()) {
                terminalStatus = RunStatus.FAILED;
                terminalReason = "single_submit_not_passed";
                break;
            }
            if (attemptNumber < spec.submit().maxAttempts() && outcome.agentDeclaredDone()) {
                // 轻量 stop-hook：Agent 自称完成但未过线，拽回来继续。
                trace.log(TraceEventType.STOP_HOOK_TRIGGERED, attemptId, Map.of(
                        "reason", "below_pass_score", "score", result.score(),
                        "pass_score", spec.scoring().passScore()));
            }
            cooldown(spec, attemptNumber);
        }

        if (terminalStatus == null) {
            terminalStatus = RunStatus.FAILED;
            terminalReason = "max_attempts_reached";
        }

        // 完整性复核：run 期间 hidden 被动过 → 无论过程结果如何，整体作废。
        String hiddenAfter = Hashes.sha256OfDir(ctx.hiddenDir());
        if (!hiddenAfter.equals(state.hiddenFingerprintBefore())) {
            trace.log(TraceEventType.ERROR, null, Map.of(
                    "reason", "hidden_fingerprint_mismatch",
                    "expected", state.hiddenFingerprintBefore(), "actual", hiddenAfter));
            terminalStatus = RunStatus.INTEGRITY_BROKEN;
            terminalReason = "hidden 目录在 run 期间被修改";
        }
        String traceProblem = traceIntegrityProblem(ctx.traceFile(), state.runId());
        if (traceProblem != null) {
            trace.log(TraceEventType.ERROR, null, Map.of(
                    "reason", "trace_integrity_broken", "detail", traceProblem));
            terminalStatus = RunStatus.ERROR;
            terminalReason = "trace_integrity_broken: " + traceProblem;
        }

        Optional<RunState.AttemptRecord> best =
                BestAttemptSelector.select(state.attempts(), spec.scoring().selection());
        String bestId = best.map(RunState.AttemptRecord::attemptId).orElse(null);
        if (terminalStatus == RunStatus.PASSED || terminalStatus == RunStatus.FAILED) {
            Map<String, Object> selectionPayload = new LinkedHashMap<>();
            selectionPayload.put("policy", spec.scoring().selection().jsonName());
            selectionPayload.put("best_attempt_id", bestId == null ? "" : bestId);
            selectionPayload.put("best_score", best.map(RunState.AttemptRecord::score).orElse(null));
            trace.log(TraceEventType.FINAL_SELECTION, null, selectionPayload);
        }
        state = state.finish(terminalStatus, terminalReason, bestId);
        RunStateStore.save(ctx.runStateFile(), state);
        return finalize(ctx, state, trace, best.orElse(null), traceSecret);
    }

    // ---------------------------------------------------------------- steps

    private static RunOutcome finalize(TaskContext ctx, RunState state,
                                       TraceLogger trace, RunState.AttemptRecord best,
                                       byte[] traceSecret) {
        trace.log(TraceEventType.RUN_COMPLETED, null, Map.of(
                "status", state.status().name(),
                "reason", state.statusReason() == null ? "" : state.statusReason(),
                "total_attempts", state.attempts().size(),
                "best_attempt_id", state.bestAttemptId() == null ? "" : state.bestAttemptId()));
        trace.close();

        ReportGenerator.Paths reportPaths = ReportGenerator.generate(ctx.runDir(), traceSecret);
        log.info("run {} 结束: {}（{}）", state.runId(), state.status(), state.statusReason());
        return new RunOutcome(state.runId(), ctx.runDir(), state.status(),
                best == null ? null : best.score(),
                best == null ? null : best.attemptId(),
                reportPaths.reportJson(), reportPaths.reportMd());
    }

    private static void writeInstructions(TaskContext ctx) {
        try {
            Files.writeString(ctx.instructionsFile(), InstructionsRenderer.render(ctx), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 instructions.md 失败", e);
        }
    }

    private static void writeJudgeResult(TaskContext ctx, String attemptId, JudgeResult result) {
        try {
            Files.createDirectories(ctx.judgeDir());
            Files.writeString(ctx.judgeDir().resolve(attemptId + ".judge.json"),
                    com.agenteval.util.Jsons.json().writeValueAsString(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入评分结果失败", e);
        }
    }

    private static String nextAttemptId(TaskSpec spec, int currentAttemptNumber) {
        return currentAttemptNumber < spec.submit().maxAttempts()
                ? Ids.attemptId(currentAttemptNumber + 1) : null;
    }

    private static void cooldown(TaskSpec spec, int attemptNumber) {
        int seconds = spec.submit().cooldownSeconds();
        if (seconds > 0 && attemptNumber < spec.submit().maxAttempts()) {
            try {
                Thread.sleep(Duration.ofSeconds(seconds).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String traceIntegrityProblem(Path traceFile, String runId) {
        java.util.List<JsonNode> events;
        try {
            events = TraceLogger.readAll(traceFile);
        } catch (RuntimeException e) {
            return "trace_unreadable: " + e.getMessage();
        }
        if (events.isEmpty()) {
            return "trace_empty";
        }
        JsonNode first = events.get(0);
        if (!runId.equals(first.path("run_id").asText())) {
            return "first_event_run_id_mismatch";
        }
        if (first.path("seq").asLong(-1) != 1 || !"run_started".equals(first.path("type").asText())) {
            return "missing_initial_run_started";
        }
        long expectedSeq = 1;
        for (JsonNode event : events) {
            long actualSeq = event.path("seq").asLong(-1);
            if (actualSeq != expectedSeq) {
                return "seq_gap_expected_" + expectedSeq + "_actual_" + actualSeq;
            }
            expectedSeq++;
        }
        return null;
    }

    /**
     * 便捷入口：无 map 修改需要的调用方使用。
     *
     * @param taskDir 任务目录
     * @param runsRoot runs 根目录
     * @param modelName 模型标识
     * @param adapter 适配器
     * @return run 结论
     */
    public static RunOutcome run(Path taskDir, Path runsRoot, String modelName, AgentAdapter adapter) {
        return execute(new RunConfig(taskDir, runsRoot, modelName, adapter, null));
    }

    /**
     * 便捷入口：恢复一次 run。
     *
     * @param runDir 待恢复的 run 目录
     * @param adapter 适配器
     * @return run 结论
     */
    public static RunOutcome resume(Path runDir, AgentAdapter adapter) {
        return execute(new RunConfig(null, null, null, adapter, runDir));
    }
}
