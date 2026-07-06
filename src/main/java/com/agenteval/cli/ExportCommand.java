package com.agenteval.cli;

import com.agenteval.trace.OtlpTraceExporter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval export}：把 run 的 trace.jsonl 导出为 OTLP/OpenInference JSON，
 * 供 Arize Phoenix / Langfuse / 任意 OTel Collector 摄取（外接看板，不自建可视化）。
 *
 * <p>用法：
 * <pre>{@code
 * # 写出 OTLP JSON 文件（默认落在 run 目录 report/ 下）
 * agent-eval export --run runs/tool-call-001/run_xxx
 *
 * # 直接推给接受 OTLP/JSON 的收集器（OTel Collector 的 4318 端口等）
 * agent-eval export --run runs/tool-call-001/run_xxx \
 *     --endpoint http://localhost:4318/v1/traces
 * }</pre>
 *
 * <p>导出是纯读、幂等的：span/trace id 由 run/attempt/call id 确定性派生，
 * 重复导出同一 run 不会在看板里产生分叉数据。
 *
 * <p>注意：本命令按 OTLP/HTTP 规范以 {@code application/json} 推送。个别后端
 * （如 Arize Phoenix 17.x 的 {@code /v1/traces}）只收 protobuf 会回 415，
 * 此时经 OTel Collector 中转，或用 {@code research/poc/phoenix/push_otlp_json.py}
 * 把导出的 JSON 转成 protobuf 直推（已实测 Phoenix 可收）。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "export", mixinStandardHelpOptions = true,
        description = "导出 run 的 trace 为 OTLP/OpenInference JSON（可选直接 POST 到看板）")
public final class ExportCommand implements Callable<Integer> {

    @Option(names = "--run", required = true, description = "run 目录")
    private Path runDir;

    @Option(names = "--out", description = "输出文件（默认 <run>/report/trace.otlp.json）")
    private Path outFile;

    @Option(names = "--endpoint",
            description = "OTLP/HTTP 端点（如 http://localhost:6006/v1/traces）；提供则额外 POST")
    private String endpoint;

    @Override
    public Integer call() {
        ObjectNode request;
        try {
            request = OtlpTraceExporter.build(runDir);
        } catch (RuntimeException e) {
            System.err.println("错误: " + e.getMessage());
            return 1;
        }

        Path target = outFile != null ? outFile : runDir.resolve("report/trace.otlp.json");
        OtlpTraceExporter.exportToFile(runDir, target);
        System.out.println("已导出 " + OtlpTraceExporter.spanCount(request) + " 个 span → " + target);

        if (endpoint != null && !endpoint.isBlank()) {
            try {
                int status = OtlpTraceExporter.post(request, endpoint);
                if (status / 100 != 2) {
                    System.err.println("错误: 端点返回 HTTP " + status + "（" + endpoint + "）");
                    return 1;
                }
                System.out.println("已推送到 " + endpoint + "（HTTP " + status + "）");
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String reason = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                System.err.println("错误: 推送失败（" + endpoint + "）: " + reason);
                return 1;
            }
        }
        return 0;
    }
}
