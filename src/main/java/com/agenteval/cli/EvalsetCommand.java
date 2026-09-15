package com.agenteval.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval evalset}：私有测评集工程化辅助（当前提供 {@code init} 脚手架）。
 *
 * <p>私有测评集是小团队落地 AgentEval-Lite 的默认入口：集合目录承载团队自己的任务库、
 * Agent 接入脚本、对比清单和运行产物根目录；它独立于内置 {@code tasks/}，避免把业务题库混进
 * 框架自测门禁。
 *
 * @author shiyongyin
 * @since 0.4.0
 */
@Command(name = "evalset", mixinStandardHelpOptions = true, description = "私有测评集工程化辅助",
        subcommands = {EvalsetCommand.InitCommand.class})
public final class EvalsetCommand {

    /**
     * {@code evalset init} 子命令：生成私有测评集骨架。
     */
    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "生成私有测评集骨架（tasks/、agents.yaml、接入脚本与落地说明）")
    public static final class InitCommand implements Callable<Integer> {

        /** 测评集 id：仓库目录名，保持 kebab-case，避免空格、斜杠和大小写混用。 */
        private static final Pattern ID_PATTERN =
                Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

        @Option(names = "--id", required = true,
                description = "测评集 id（kebab-case，如 ops-agent；同时作为目录名）")
        private String evalsetId;

        @Option(names = "--evalsets-root", defaultValue = "evalsets",
                description = "测评集根目录（默认 ${DEFAULT-VALUE}）")
        private Path evalsetsRoot;

        @Override
        public Integer call() {
            if (!ID_PATTERN.matcher(evalsetId).matches()) {
                System.err.println("错误: 测评集 id 需为 kebab-case（如 ops-agent），实际: " + evalsetId);
                return 1;
            }
            Path evalsetDir = evalsetsRoot.resolve(evalsetId);
            if (Files.exists(evalsetDir)) {
                System.err.println("错误: 目录已存在，拒绝覆盖: " + evalsetDir);
                return 1;
            }

            try {
                for (Map.Entry<String, String> entry : templates().entrySet()) {
                    Path file = evalsetDir.resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.getValue().replace("__EVALSET_ID__", evalsetId),
                            StandardCharsets.UTF_8);
                }
                evalsetDir.resolve("scripts/run-agent.sh").toFile().setExecutable(true, false);
            } catch (IOException | UncheckedIOException e) {
                System.err.println("错误: 写入测评集脚手架失败: " + e.getMessage());
                return 2;
            }

            System.out.println("已生成私有测评集脚手架: " + evalsetDir);
            System.out.println("  ├── README.md              # 小团队落地说明与标准命令");
            System.out.println("  ├── agents.yaml            # 多 Agent 横评清单");
            System.out.println("  ├── scripts/run-agent.sh   # 团队 Agent 接入包装器（claude / codex / custom）");
            System.out.println("  ├── ci/evalset-smoke.yml   # PR smoke 门禁工作流模板（复制到 .github/workflows/）");
            System.out.println("  └── tasks/.gitkeep         # 私有任务库（用 task init 填充）");
            System.out.println();
            System.out.println("下一步:");
            System.out.println("  1. bin/agent-eval task init --id first-task-001 --tasks-root "
                    + evalsetDir.resolve("tasks"));
            System.out.println("  2. 改写 task.yaml/work/hidden/samples 后跑 validate + scripted 回放");
            System.out.println("  3. 在 " + evalsetDir.resolve("scripts/run-agent.sh")
                    + " 接入真实 Agent，再用 agents.yaml 横评");
            return 0;
        }

        /** 模板文件名（相对测评集根）→ classpath 资源路径；内容与仓库 {@code evalsets/_template/} 保持一致。 */
        private static final Map<String, String> TEMPLATE_RESOURCES = Map.of(
                "README.md", "evalset-template/README.md",
                "agents.yaml", "evalset-template/agents.yaml",
                "scripts/run-agent.sh", "evalset-template/scripts/run-agent.sh",
                "ci/evalset-smoke.yml", "evalset-template/ci/evalset-smoke.yml");

        private static Map<String, String> templates() {
            Map<String, String> files = new LinkedHashMap<>();
            for (String name : new String[] {
                    "README.md", "agents.yaml", "scripts/run-agent.sh", "ci/evalset-smoke.yml"}) {
                files.put(name, readResource(TEMPLATE_RESOURCES.get(name)));
            }
            files.put("tasks/.gitkeep", "");
            return files;
        }

        private static String readResource(String resource) {
            try (InputStream in = EvalsetCommand.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new UncheckedIOException(new IOException("缺少脚手架资源: " + resource));
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读取脚手架资源失败: " + resource, e);
            }
        }
    }
}
