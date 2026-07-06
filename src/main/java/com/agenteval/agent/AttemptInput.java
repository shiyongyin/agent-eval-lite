package com.agenteval.agent;

import com.agenteval.task.TaskContext;
import com.agenteval.tool.ToolAccess;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 一次 attempt 的输入：Runner 交给 Agent 适配器的全部信息。
 *
 * @param context run 上下文
 * @param attemptId 本轮 attempt id（如 {@code attempt_002}）
 * @param attemptNumber 本轮序号（1 起）
 * @param instructionsFile 任务说明文件
 * @param previousFeedbackFile 上一轮受控反馈（首轮为 {@code null}）
 * @param timeout 本轮执行预算
 * @param toolAccess 本 run 的工具调用能力（进程内网关 + 常驻服务端点/凭证；可为 {@code null}）
 * @author shiyongyin
 * @since 0.1.0
 */
public record AttemptInput(
        TaskContext context,
        String attemptId,
        int attemptNumber,
        Path instructionsFile,
        Path previousFeedbackFile,
        Duration timeout,
        ToolAccess toolAccess) {

    /** @return 本轮提交文件的期望落点 */
    public Path expectedSubmissionFile() {
        return context.inboxDir().resolve(attemptId + ".json");
    }
}
