package com.agenteval.task;

import java.nio.file.Path;

/**
 * 一次 run 的路径上下文：任务目录 + 运行产物目录的全部落点，构造后不可变。
 *
 * <p>目录即安全边界（详见设计 §2.4 权限矩阵）：Agent 只应触碰 {@code workspace/}、
 * {@code inbox/}、{@code feedback/} 与 {@code instructions.md}；{@code hidden/}、
 * {@code judge/}、{@code traces/}、{@code run_state.json} 是评审侧禁区。
 *
 * @param spec 任务规格
 * @param taskDir 任务目录（绝对路径）
 * @param runDir 本次运行根目录（绝对路径）
 * @author shiyongyin
 * @since 0.1.0
 */
public record TaskContext(TaskSpec spec, Path taskDir, Path runDir) {

    /**
     * 创建上下文（全部路径归一为绝对路径）。
     *
     * @param spec 任务规格
     * @param taskDir 任务目录
     * @param runDir 运行根目录
     * @return 上下文实例
     */
    public static TaskContext of(TaskSpec spec, Path taskDir, Path runDir) {
        return new TaskContext(spec, taskDir.toAbsolutePath().normalize(), runDir.toAbsolutePath().normalize());
    }

    /** @return 任务的 work/ 原始目录（只读母本） */
    public Path workSourceDir() {
        return taskDir.resolve("work");
    }

    /** @return 任务的 hidden/ 目录（Agent 永不可见） */
    public Path hiddenDir() {
        return taskDir.resolve("hidden");
    }

    /** @return Agent 的工作副本目录 */
    public Path workspaceDir() {
        return runDir.resolve("workspace");
    }

    /** @return Agent 提交收件目录（唯一提交通道） */
    public Path inboxDir() {
        return runDir.resolve("inbox");
    }

    /** @return 受控反馈目录（Agent 可读） */
    public Path feedbackDir() {
        return runDir.resolve("feedback");
    }

    /** @return 完整评分结果目录（Agent 禁区） */
    public Path judgeDir() {
        return runDir.resolve("judge");
    }

    /** @return trace 目录（Agent 禁区） */
    public Path tracesDir() {
        return runDir.resolve("traces");
    }

    /** @return trace.jsonl 文件 */
    public Path traceFile() {
        return tracesDir().resolve("trace.jsonl");
    }

    /** @return 报告目录 */
    public Path reportDir() {
        return runDir.resolve("report");
    }

    /** @return agent 进程输出日志目录 */
    public Path agentLogsDir() {
        return runDir.resolve("agent-logs");
    }

    /** @return 渲染给 Agent 的任务说明文件 */
    public Path instructionsFile() {
        return runDir.resolve("instructions.md");
    }

    /** @return run 元数据文件 */
    public Path metaFile() {
        return runDir.resolve("meta.json");
    }

    /** @return resume 快照文件 */
    public Path runStateFile() {
        return runDir.resolve("run_state.json");
    }

    /** @return workspace 基线指纹文件（judge 禁区内，供 changed_files 核验） */
    public Path baselineFile() {
        return judgeDir().resolve("baseline.json");
    }
}
