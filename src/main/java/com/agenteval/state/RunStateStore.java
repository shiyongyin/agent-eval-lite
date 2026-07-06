package com.agenteval.state;

import com.agenteval.util.Jsons;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * run_state.json 的读写器。写入走「临时文件 + 原子改名」，
 * 保证进程在任意时刻被杀，快照要么是旧的完整版本、要么是新的完整版本，
 * 不会出现半截 JSON 导致 resume 失败。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class RunStateStore {

    private RunStateStore() {
    }

    /**
     * 原子保存快照。
     *
     * @param stateFile run_state.json 路径
     * @param state 快照
     */
    public static void save(Path stateFile, RunState state) {
        try {
            Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(temp, Jsons.json().writeValueAsString(state), StandardCharsets.UTF_8);
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("保存 run_state 失败: " + stateFile, e);
        }
    }

    /**
     * 读取快照。
     *
     * @param stateFile run_state.json 路径
     * @return 快照；文件不存在时返回 {@code null}
     */
    public static RunState load(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return null;
        }
        try {
            return Jsons.json().readValue(stateFile.toFile(), RunState.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 run_state 失败: " + stateFile, e);
        }
    }
}
