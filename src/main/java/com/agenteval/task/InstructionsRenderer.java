package com.agenteval.task;

import java.util.Locale;

/**
 * 渲染 Agent 视角的任务说明 {@code instructions.md}。
 *
 * <p>这是 Agent 与框架之间的<strong>全部</strong>约定来源：任务陈述、可见材料、工具用法、
 * 提交契约、反馈位置与评分口径。渲染内容绝不包含 hidden 目录的任何路径或规则细节——
 * Agent 知道「会被隐藏规则评分」，但不知道规则是什么（Work/Judge 隔离）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class InstructionsRenderer {

    private InstructionsRenderer() {
    }

    /**
     * 渲染任务说明全文。
     *
     * @param ctx run 上下文
     * @return instructions.md 的 Markdown 全文
     */
    public static String render(TaskContext ctx) {
        TaskSpec spec = ctx.spec();
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务：").append(spec.taskName()).append("\n\n");
        sb.append("- 任务 ID：`").append(spec.taskId()).append("`\n");
        sb.append("- 任务类型：`").append(spec.taskType().jsonName()).append("`\n");
        sb.append("- 最大提交次数：").append(spec.submit().maxAttempts())
                .append("；通过线：").append(spec.scoring().passScore())
                .append("/").append(spec.scoring().maxScore()).append("\n\n");

        sb.append("## 任务说明\n\n").append(spec.agentBrief().trim()).append("\n\n");

        sb.append("## 你的工作区\n\n");
        sb.append("- 工作区（可自由读写）：`").append(ctx.workspaceDir()).append("`\n");
        if (!spec.visibleContext().isEmpty()) {
            sb.append("- 任务材料（相对工作区，`work/` 前缀对应工作区根）：\n");
            for (String rel : spec.visibleContext()) {
                sb.append("  - `").append(rel.replaceFirst("^work/", "")).append("`\n");
            }
        }
        sb.append("\n**边界约定**：你只能访问上述工作区、本说明文件与下文的反馈目录。")
                .append("禁止读取或探测评估框架的其他目录（包括任何评审规则与期望数据）；")
                .append("违规会被完整性校验与泄露探针识破并直接判负。\n\n");

        if (!spec.allowedTools().isEmpty()) {
            sb.append("## 可用工具\n\n");
            sb.append("以下工具**必须**通过评估框架网关调用（已注入环境变量 `AEL_RUN_DIR`）：\n\n");
            for (TaskSpec.AllowedTool tool : spec.allowedTools()) {
                sb.append("- `").append(tool.name()).append("`：").append(tool.description()).append("\n");
            }
            sb.append("\n调用方式：\n\n```bash\nagent-eval tool call <tool-name> --input '<json>'\n```\n\n");
            sb.append("返回 JSON 中的 `call_id` 必须原样填入提交的 `tool_calls_used[].call_id`——")
                    .append("评审会核对调用记录，未真实调用而编造 call_id 会被判负。\n\n");
        }

        sb.append("## 提交方式（唯一有效通道）\n\n");
        sb.append("把提交 JSON 写入收件目录（自然语言输出不计分）：\n\n");
        sb.append("- 收件目录：`").append(ctx.inboxDir()).append("`\n");
        sb.append("- 文件名：`attempt_001.json`、`attempt_002.json` … 按提交次数递增\n");
        sb.append("- 当前应使用的文件名会在每轮反馈中给出\n\n");
        sb.append("### 提交格式（submission_type = `").append(submissionType(spec)).append("`）\n\n");
        sb.append(envelopeDoc());
        sb.append(typedDoc(submissionType(spec)));

        sb.append("## 多轮反馈\n\n");
        sb.append("- 每次提交后，评审反馈写入：`").append(ctx.feedbackDir()).append("`（`attempt_NNN.feedback.json`）\n");
        sb.append("- 若未达通过线且还有剩余次数，请根据反馈修正后再次提交\n\n");

        sb.append("## 评分维度\n\n");
        sb.append("| 维度 | 权重 |\n|---|---|\n");
        for (TaskSpec.Dimension dim : spec.scoring().dimensions()) {
            sb.append("| ").append(dim.name()).append(" | ").append(dim.weight()).append(" |\n");
        }
        sb.append("\n评分由隐藏评审执行，规则不公开；反馈中会给出可改进方向。\n");
        return sb.toString();
    }

    private static String submissionType(TaskSpec spec) {
        String schema = spec.submit().schema();
        if (schema.startsWith("builtin:")) {
            return schema.substring("builtin:".length()).toLowerCase(Locale.ROOT);
        }
        return spec.taskType().jsonName();
    }

    private static String envelopeDoc() {
        return """
                必填信封字段：

                | 字段 | 类型 | 说明 |
                |---|---|---|
                | `schema_version` | int | 固定 `1` |
                | `task_id` | string | 本任务 ID |
                | `attempt_id` | string | 如 `attempt_001`，与文件名一致 |
                | `submission_type` | string | 见上方类型 |
                | `summary` | string | 本次提交做了什么（≥8 字符） |
                | `known_risks` | string[] | 已知风险/边界（可为空数组，但必须存在） |
                | `needs_human_review` | boolean | 是否需要人工复核 |

                可选信封字段：`evidence[]`（`{type: file|tool_call|url, source, quote_or_ref}`）、
                `tool_calls_used[]`（`{tool_name, call_id, purpose}`）。

                """;
    }

    private static String typedDoc(String type) {
        return switch (type) {
            case "code_fix" -> """
                    `code_fix` 追加必填：

                    - `changed_files[]`：`{path: 相对工作区路径, change_summary}`（至少 1 项；必须真实修改了工作区文件）
                    - `tests_run[]`：`{command, result: passed|failed|not_run}`

                    """;
            case "api_payload" -> """
                    `api_payload` 追加必填：

                    - `final_payload`：最终产出的 JSON payload（被评对象）

                    """;
            case "tool_call" -> """
                    `tool_call` 追加必填：

                    - `final_payload`：最终产出的 JSON payload
                    - `tool_calls_used[]`：至少 1 项，`call_id` 必须来自真实工具调用返回
                    - `validation_result`：对象，说明 payload 关键字段如何由工具返回值得出

                    """;
            case "document", "review" -> """
                    追加必填：

                    - `deliverable`：`{type: markdown|json, content: 字符串}`
                    - `sources[]`：引用的任务材料路径（如 `work/docs/xxx.md`，至少 1 项）
                    - `confidence`：0~1 之间的置信度

                    """;
            default -> """
                    `generic` 追加必填：

                    - `answer`：结构化结果对象

                    """;
        };
    }
}
