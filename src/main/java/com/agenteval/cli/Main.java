package com.agenteval.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI 根命令：{@code agent-eval}。
 *
 * <p>退出码约定：0 = 命令正常完成（评估「未通过」也是正常完成，verdict 看输出与报告）；
 * 1 = 参数或输入错误；2 = 框架内部故障。CI 需要按通过与否置退出码时，
 * 使用 {@code run --fail-on-not-passed}。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(
        name = "agent-eval",
        mixinStandardHelpOptions = true,
        version = "agent-eval-lite 0.1.0",
        description = "企业内部 AI Agent 测试脚手架（AgentEval-Lite）",
        subcommands = {
                RunCommand.class,
                SuiteCommand.class,
                JudgeCommand.class,
                ReportCommand.class,
                ExportCommand.class,
                ValidateCommand.class,
                ListCommand.class,
                HistoryCommand.class,
                EvalsetCommand.class,
                ToolCommand.class,
                TaskCommand.class
        })
public final class Main {

    /**
     * CLI 入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }
}
