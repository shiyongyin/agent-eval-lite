package com.agenteval.agent;

/**
 * Agent 适配器 SPI：把「任何能产出提交文件的东西」接入评估循环。
 *
 * <p>框架不关心 Agent 内部如何思考，只关心三件事：给它任务说明与反馈、
 * 限定它在 workspace 里干活、收它写进 inbox 的提交。因此适配器面积极小——
 * 人肉、脚本回放、CLI agent（claude/cursor-agent/自研）都是一个方法的事。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public interface AgentAdapter {

    /** @return 适配器名（进入 meta.json 与报告） */
    String name();

    /**
     * 执行一轮 attempt：驱动 Agent 工作直至其产出提交或放弃。
     *
     * <p>实现必须自行遵守 {@link AttemptInput#timeout()}；返回后 Runner 才会校验与评分。
     *
     * @param input 本轮输入
     * @return 本轮结果
     */
    AttemptOutcome runAttempt(AttemptInput input);
}
