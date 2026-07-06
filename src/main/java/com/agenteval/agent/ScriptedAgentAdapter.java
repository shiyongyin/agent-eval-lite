package com.agenteval.agent;

import com.agenteval.tool.ToolCallResult;
import com.agenteval.tool.ToolGateway;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 脚本回放适配器：按 replay.yaml 逐轮模拟一个 Agent 的行为，
 * 是框架自测与 CI 回归的确定性驱动器（不依赖任何真实模型）。
 *
 * <p>replay.yaml 结构：
 * <pre>{@code
 * attempts:
 *   - apply_files:                       # 可选：模拟 Agent 修改工作区
 *       - target: src/PriceCalculator.java   # 相对 workspace
 *         source: samples/PriceCalculator.fixed.java  # 相对任务目录
 *     tool_calls:                        # 可选：经真实网关调用工具（产生 trace 与 call_id）
 *       - tool: user.lookup
 *         input: {user_id: "u_1001"}
 *     submission_file: samples/attempt-fail.json      # 相对任务目录
 * }</pre>
 *
 * <p>提交模板支持占位符：{@code {attempt_id}} 替换为当前轮 id；
 * {@code {tool_call_N}} 替换为本轮第 N 次工具调用返回的真实 call_id——
 * 这保证回放出的提交能通过「call_id 必须真实存在」的评审核验。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class ScriptedAgentAdapter implements AgentAdapter {

    private final Path replayFile;
    private List<JsonNode> attempts;

    /**
     * 构造适配器。
     *
     * @param replayFile replay.yaml 路径
     */
    public ScriptedAgentAdapter(Path replayFile) {
        this.replayFile = replayFile;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public AttemptOutcome runAttempt(AttemptInput input) {
        ensureLoaded();
        int index = input.attemptNumber() - 1;
        if (index >= attempts.size()) {
            return AttemptOutcome.noMoreInput();
        }
        JsonNode step = attempts.get(index);
        Path taskDir = input.context().taskDir();

        for (JsonNode apply : step.path("apply_files")) {
            Path source = taskDir.resolve(apply.path("source").asText());
            Path target = input.context().workspaceDir().resolve(apply.path("target").asText());
            try {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("回放 apply_files 失败: " + source, e);
            }
        }

        List<String> callIds = new ArrayList<>();
        if (!step.path("tool_calls").isEmpty()) {
            // 用本 run 的签名网关（经 toolAccess 注入），使回放产生的 tool_call 事件带合法签名、可被判分核验；
            // 仅在无 toolAccess 的历史调用路径下才回退到不签名网关。
            ToolGateway gateway = input.toolAccess() != null
                    ? input.toolAccess().gateway()
                    : ToolGateway.forRun(input.context().runDir());
            for (JsonNode call : step.path("tool_calls")) {
                ToolCallResult result = gateway.call(call.path("tool").asText(), call.path("input"));
                callIds.add(result.callId());
            }
        }

        String submissionRel = step.path("submission_file").asText("");
        if (submissionRel.isBlank()) {
            return new AttemptOutcome(null, true, 0, null, false);
        }
        try {
            String template = Files.readString(taskDir.resolve(submissionRel), StandardCharsets.UTF_8);
            String rendered = template.replace("{attempt_id}", input.attemptId());
            for (int i = 0; i < callIds.size(); i++) {
                rendered = rendered.replace("{tool_call_" + (i + 1) + "}", callIds.get(i));
            }
            Path target = input.expectedSubmissionFile();
            Files.writeString(target, rendered, StandardCharsets.UTF_8);
            return AttemptOutcome.submitted(target);
        } catch (IOException e) {
            throw new UncheckedIOException("回放提交失败: " + submissionRel, e);
        }
    }

    private void ensureLoaded() {
        if (attempts != null) {
            return;
        }
        try {
            JsonNode root = Jsons.yaml().readTree(Files.readString(replayFile, StandardCharsets.UTF_8));
            List<JsonNode> loaded = new ArrayList<>();
            root.path("attempts").forEach(loaded::add);
            if (loaded.isEmpty()) {
                throw new IllegalArgumentException("replay 文件不含任何 attempts: " + replayFile);
            }
            this.attempts = loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("读取 replay 文件失败: " + replayFile, e);
        }
    }
}
