package com.agenteval.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 人肉适配器：把「人」当成被评 Agent，用于调任务与演示。
 *
 * <p>两种形态：
 * <ul>
 *   <li><strong>单发</strong>（{@code --submission} 提供文件）：首轮把该文件投递为提交，
 *       后续轮次无输入即宣告耗尽；</li>
 *   <li><strong>交互</strong>（未提供文件）：每轮打印任务说明与收件路径，
 *       等待人把提交写进 inbox 后回车（输入 {@code q} 放弃）。</li>
 * </ul>
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class ManualAgentAdapter implements AgentAdapter {

    private final Path oneShotSubmission;

    /**
     * 构造适配器。
     *
     * @param oneShotSubmission 单发提交文件；{@code null} 表示交互模式
     */
    public ManualAgentAdapter(Path oneShotSubmission) {
        this.oneShotSubmission = oneShotSubmission;
    }

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public AttemptOutcome runAttempt(AttemptInput input) {
        if (oneShotSubmission != null) {
            if (input.attemptNumber() > 1) {
                return AttemptOutcome.noMoreInput();
            }
            Path target = input.expectedSubmissionFile();
            try {
                Files.copy(oneShotSubmission, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("复制提交文件失败: " + oneShotSubmission, e);
            }
            return AttemptOutcome.submitted(target);
        }
        return interactiveAttempt(input);
    }

    private AttemptOutcome interactiveAttempt(AttemptInput input) {
        System.out.println();
        System.out.println("=== 人工轮次 " + input.attemptId() + " ===");
        System.out.println("任务说明: " + input.instructionsFile());
        if (input.previousFeedbackFile() != null) {
            System.out.println("上轮反馈: " + input.previousFeedbackFile());
        }
        System.out.println("请把提交写入: " + input.expectedSubmissionFile());
        System.out.print("完成后回车继续（输入 q 放弃）> ");
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line != null && line.trim().equalsIgnoreCase("q")) {
                return AttemptOutcome.noMoreInput();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取控制台输入失败", e);
        }
        Path expected = input.expectedSubmissionFile();
        return Files.isRegularFile(expected)
                ? AttemptOutcome.submitted(expected)
                : new AttemptOutcome(null, true, 0, null, false);
    }
}
