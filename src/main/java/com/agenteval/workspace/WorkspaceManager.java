package com.agenteval.workspace;

import com.agenteval.task.TaskContext;
import com.agenteval.util.Dirs;
import com.agenteval.util.Hashes;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 工作区管理：run 目录初始化、work → workspace 复制、文件级基线指纹。
 *
 * <p>基线指纹（{@code judge/baseline.json}：相对路径 → SHA-256）是 code_fix 类任务
 * {@code changed_files_verified} 核验的物证——Agent 申报的修改必须相对基线真实发生。
 * 基线放在 judge 禁区而非 workspace，避免 Agent「参考」它。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class WorkspaceManager {

    private WorkspaceManager() {
    }

    /**
     * run 初始化产出的指纹组。
     *
     * @param workspaceFingerprint workspace 目录树指纹（初始态）
     * @param hiddenFingerprint hidden 目录树指纹（防篡改基准）
     */
    public record Fingerprints(String workspaceFingerprint, String hiddenFingerprint) {
    }

    /**
     * 初始化一次 run 的全部目录并建立指纹基线。
     *
     * @param ctx run 上下文
     * @return 初始指纹组
     */
    public static Fingerprints prepare(TaskContext ctx) {
        try {
            Files.createDirectories(ctx.runDir());
            Files.createDirectories(ctx.inboxDir());
            Files.createDirectories(ctx.feedbackDir());
            Files.createDirectories(ctx.judgeDir());
            Files.createDirectories(ctx.tracesDir());
            Files.createDirectories(ctx.reportDir());
            Files.createDirectories(ctx.agentLogsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("创建 run 目录失败: " + ctx.runDir(), e);
        }
        Dirs.copyTree(ctx.workSourceDir(), ctx.workspaceDir());

        Map<String, String> baseline = fileBaseline(ctx.workspaceDir());
        try {
            Jsons.json().writeValue(ctx.baselineFile().toFile(), baseline);
        } catch (IOException e) {
            throw new UncheckedIOException("写入基线指纹失败", e);
        }
        return new Fingerprints(
                Hashes.sha256OfDir(ctx.workspaceDir()),
                Hashes.sha256OfDir(ctx.hiddenDir()));
    }

    /**
     * 计算目录的文件级基线：相对路径（{@code /} 分隔）→ 内容 SHA-256，按路径排序。
     *
     * @param dir 目标目录
     * @return 有序基线映射
     */
    public static Map<String, String> fileBaseline(Path dir) {
        Map<String, String> baseline = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return baseline;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                    .forEach(file -> baseline.put(
                            dir.relativize(file).toString().replace('\\', '/'),
                            Hashes.sha256OfFile(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("扫描目录失败: " + dir, e);
        }
        return baseline;
    }

    /**
     * 读取已落盘的基线指纹。
     *
     * @param baselineFile 基线文件
     * @return 基线映射；文件不存在返回 {@code null}
     */
    public static Map<String, String> readBaseline(Path baselineFile) {
        if (!Files.isRegularFile(baselineFile)) {
            return null;
        }
        try {
            return Jsons.json().readValue(baselineFile.toFile(), new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("读取基线指纹失败: " + baselineFile, e);
        }
    }
}
