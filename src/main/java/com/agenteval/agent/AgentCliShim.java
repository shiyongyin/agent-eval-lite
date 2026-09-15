package com.agenteval.agent;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在 run 目录的框架私有区（{@code .ael/bin/}）放一个 {@code agent-eval} 垫片，并把它前置到 cli Agent 子进程的 PATH。
 *
 * <p>instructions.md 告诉 Agent 用 {@code agent-eval tool call <tool> --input ...} 调工具，这个承诺必须由框架自己兑现：
 * 垫片用当前 JVM 与 classpath 启动 {@code com.agenteval.cli.Main}，无论框架是从 fat jar 还是从 IDE/测试 classpath
 * 运行都成立，用户不需要把 {@code bin/} 加进 PATH。Docker 模式不走这里——镜像自带同名垫片，经挂入的工具 jar 回连宿主。
 *
 * @author shiyongyin
 * @since 0.5.0
 */
final class AgentCliShim {

    private static final String MAIN_CLASS = "com.agenteval.cli.Main";

    private AgentCliShim() {
    }

    /**
     * 确保垫片存在并返回其所在目录（幂等；同一 run 多轮复用同一文件）。
     *
     * @param runDir run 目录
     * @return 含 {@code agent-eval} 可执行文件的目录
     */
    static Path ensure(Path runDir) {
        Path binDir = runDir.resolve(".ael").resolve("bin");
        Path shim = binDir.resolve("agent-eval");
        if (Files.isRegularFile(shim)) {
            return binDir;
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        String script = "#!/bin/sh\n"
                + "# AgentEval-Lite 自动生成：让 Agent 子进程里的 `agent-eval` 指向启动本次评估的框架本体。\n"
                + "exec " + shellQuote(java) + " -cp " + shellQuote(classpath) + " " + MAIN_CLASS + " \"$@\"\n";
        try {
            Files.createDirectories(binDir);
            Files.writeString(shim, script, StandardCharsets.UTF_8);
            if (!shim.toFile().setExecutable(true, false)) {
                throw new IOException("无法给垫片加可执行位: " + shim);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入 agent-eval 垫片失败: " + shim, e);
        }
        return binDir;
    }

    /**
     * 把垫片目录前置到 PATH。
     *
     * @param binDir {@link #ensure} 的返回值
     * @param inheritedPath 宿主 PATH（可为 {@code null}）
     * @return 新 PATH
     */
    static String prependToPath(Path binDir, String inheritedPath) {
        return inheritedPath == null || inheritedPath.isBlank()
                ? binDir.toString()
                : binDir + File.pathSeparator + inheritedPath;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
