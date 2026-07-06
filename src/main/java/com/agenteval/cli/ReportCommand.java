package com.agenteval.cli;

import com.agenteval.report.ReportGenerator;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval report}：从 run 目录的既有工件重建报告（纯读、幂等）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "report", mixinStandardHelpOptions = true, description = "重建指定 run 的评估报告")
public final class ReportCommand implements Callable<Integer> {

    @Option(names = "--run", required = true, description = "run 目录")
    private Path runDir;

    @Override
    public Integer call() {
        ReportGenerator.Paths paths = ReportGenerator.generate(runDir);
        System.out.println("report.json: " + paths.reportJson());
        System.out.println("report.md  : " + paths.reportMd());
        return 0;
    }
}
