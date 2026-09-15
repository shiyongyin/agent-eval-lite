package com.agenteval.state;

import com.agenteval.util.Jsons;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * run 元数据（{@code meta.json}）：把「这次评的是哪个任务、谁在答题、哪个引擎在评」
 * 固化到运行目录，使运行目录自包含——工具网关子进程、离线报告再生、resume
 * 都只凭 run 目录即可还原上下文。
 *
 * @param runId run id
 * @param taskId 任务 id
 * @param taskDir 任务目录绝对路径
 * @param agentName 被评 agent 标识：有 label 时为 label（进入报告与 history 分组），否则为适配器名
 * @param agentAdapter 适配器名（manual / scripted / cli / docker / http）；旧 meta.json 缺省为 {@code null}
 * @param modelName 模型标识（可为空字符串）
 * @param engineVersion 引擎版本
 * @param createdAt 创建时间
 * @author shiyongyin
 * @since 0.1.0
 */
public record RunMeta(
        String runId,
        String taskId,
        String taskDir,
        String agentName,
        String agentAdapter,
        String modelName,
        String engineVersion,
        Instant createdAt) {

    /**
     * 适配器名的兼容读法：旧 run 的 meta.json 没有 {@code agent_adapter}，回退到 {@code agent_name}
     * （旧语义下二者相同）。
     *
     * @return 适配器名，永不为 {@code null}
     */
    public String adapterOrAgentName() {
        return agentAdapter == null || agentAdapter.isBlank() ? agentName : agentAdapter;
    }

    /**
     * 落盘到 run 目录。
     *
     * @param metaFile meta.json 路径
     */
    public void save(Path metaFile) {
        try {
            Jsons.json().writeValue(metaFile.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 meta.json 失败: " + metaFile, e);
        }
    }

    /**
     * 从 run 目录读取。
     *
     * @param metaFile meta.json 路径
     * @return 元数据
     */
    public static RunMeta load(Path metaFile) {
        if (!Files.isRegularFile(metaFile)) {
            throw new IllegalStateException("meta.json 不存在，run 目录不完整: " + metaFile);
        }
        try {
            return Jsons.json().readValue(metaFile.toFile(), RunMeta.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 meta.json 失败: " + metaFile, e);
        }
    }
}
