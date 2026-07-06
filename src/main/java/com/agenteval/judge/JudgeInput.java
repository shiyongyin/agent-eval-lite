package com.agenteval.judge;

import com.agenteval.task.TaskSpec;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/**
 * Judge 的全部输入——只读视图。实现禁止读取此视图之外的可变状态，
 * 这是「评分可离线复现」的前提（同样的输入必须得到同样的确定性结论）。
 *
 * @param taskSpec 任务规格
 * @param taskDir 任务目录
 * @param submission 已通过 schema 校验的提交内容
 * @param submissionFile 提交文件（指纹用）
 * @param workspaceDir Agent 工作区（judge 只读；command 型 check 在临时副本上执行）
 * @param baselineFile workspace 初始基线指纹文件（可为 {@code null}，离线判分时）
 * @param traceFile trace.jsonl（可为 {@code null}；工具轨迹断言依赖它）
 * @param judgeOutputDir 评审产物目录（命令日志等落点；可为 {@code null}）
 * @param runId 当前 run id
 * @param attemptId 当前 attempt id
 * @param traceSecret trace 签名密钥（非 {@code null} 时，工具轨迹类检查只统计签名可核验的事件；
 *        {@code null} 表示无密钥可用，退化为「统计全部事件」的历史语义）
 * @author shiyongyin
 * @since 0.1.0
 */
public record JudgeInput(
        TaskSpec taskSpec,
        Path taskDir,
        JsonNode submission,
        Path submissionFile,
        Path workspaceDir,
        Path baselineFile,
        Path traceFile,
        Path judgeOutputDir,
        String runId,
        String attemptId,
        byte[] traceSecret) {

    /**
     * 兼容构造器：不携带 trace 签名密钥（离线复核无密钥、或单元测试直接驱动时使用）。
     *
     * @param taskSpec 任务规格
     * @param taskDir 任务目录
     * @param submission 提交内容
     * @param submissionFile 提交文件
     * @param workspaceDir 工作区
     * @param baselineFile 基线文件（可为 {@code null}）
     * @param traceFile trace 文件（可为 {@code null}）
     * @param judgeOutputDir 评审产物目录（可为 {@code null}）
     * @param runId run id
     * @param attemptId attempt id
     */
    public JudgeInput(TaskSpec taskSpec, Path taskDir, JsonNode submission, Path submissionFile,
                      Path workspaceDir, Path baselineFile, Path traceFile, Path judgeOutputDir,
                      String runId, String attemptId) {
        this(taskSpec, taskDir, submission, submissionFile, workspaceDir, baselineFile,
                traceFile, judgeOutputDir, runId, attemptId, null);
    }

    /** @return 任务的 hidden 目录 */
    public Path hiddenDir() {
        return taskDir.resolve("hidden");
    }
}
