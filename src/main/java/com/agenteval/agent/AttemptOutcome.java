package com.agenteval.agent;

import java.nio.file.Path;

/**
 * 一次 attempt 的执行结果（Agent 侧视角，评分之前）。
 *
 * @param submissionFile 收到的提交文件；Agent 没有产出提交时为 {@code null}
 * @param agentDeclaredDone Agent 是否自称完成（用于 stop-hook 判定）
 * @param exitCode agent 进程退出码（非进程型适配器固定 0）
 * @param agentLogFile agent 输出日志（无则为 {@code null}）
 * @param exhausted 适配器已无后续输入（脚本放完 / 单发提交已用），Runner 应停止循环
 * @author shiyongyin
 * @since 0.1.0
 */
public record AttemptOutcome(
        Path submissionFile,
        boolean agentDeclaredDone,
        int exitCode,
        Path agentLogFile,
        boolean exhausted) {

    /**
     * 构造「有提交」结果。
     *
     * @param submissionFile 提交文件
     * @return 结果
     */
    public static AttemptOutcome submitted(Path submissionFile) {
        return new AttemptOutcome(submissionFile, true, 0, null, false);
    }

    /**
     * 构造「无提交且不再有输入」结果。
     *
     * @return 结果
     */
    public static AttemptOutcome noMoreInput() {
        return new AttemptOutcome(null, true, 0, null, true);
    }
}
