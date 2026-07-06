package com.agenteval.tool;

import java.util.Locale;

/**
 * 工具网关的运行模式：决定「声明了真实后端的工具」如何被服务。
 *
 * <p>与「任务声明」的关系：{@code allowed_tools[].backend} 声明工具<em>可以</em>连真实系统，
 * 而模式决定本次 run <em>是否真的</em>外呼——契约归任务，通道归运行方，两权分离。
 * 纯 mock 工具（无 backend 声明）不受模式影响，永远走应答库。
 *
 * <p>模式经环境变量 {@code AEL_TOOL_MODE}（或系统属性 {@code ael.tool.mode}，测试用）指定，
 * 缺省 {@code replay}——CI 与常规评估永远确定性，live 必须显式开启。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
public enum ToolMode {

    /**
     * 回放模式（默认）：一律取 {@code hidden/tools/<name>.responses.yaml} 应答库，
     * 确定性、可复现、零外呼。后端工具无应答库时调用失败（fail-closed），
     * 提示先用 live 模式录制。
     */
    REPLAY,

    /**
     * 真实模式：后端工具真实外呼任务声明的 URL，并把每次交换以应答库同格式
     * 存档到 {@code <run>/tools/<name>.recorded.yaml}（复现与审计依据，
     * 也可直接晋升为任务的 replay 应答库）。
     */
    LIVE;

    /**
     * 解析当前进程的工具模式。
     *
     * @return 系统属性 {@code ael.tool.mode} &gt; 环境变量 {@code AEL_TOOL_MODE} &gt; 默认 {@code replay}
     * @throws IllegalArgumentException 取值非法时（拒绝静默回退，避免评估方以为在 live 实际在 replay）
     */
    public static ToolMode current() {
        String raw = System.getProperty("ael.tool.mode");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("AEL_TOOL_MODE");
        }
        if (raw == null || raw.isBlank()) {
            return REPLAY;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "AEL_TOOL_MODE 取值非法: " + raw + "（可选 replay / live）");
        }
    }
}
