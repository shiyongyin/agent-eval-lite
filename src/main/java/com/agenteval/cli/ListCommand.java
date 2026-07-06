package com.agenteval.cli;

import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval list}：列出任务库中的全部任务及其概要。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "list", mixinStandardHelpOptions = true, description = "列出任务库中的任务")
public final class ListCommand implements Callable<Integer> {

    @Option(names = "--tasks-root", defaultValue = "tasks", description = "任务库根目录（默认 ${DEFAULT-VALUE}）")
    private Path tasksRoot;

    @Override
    public Integer call() throws IOException {
        if (!Files.isDirectory(tasksRoot)) {
            System.err.println("任务库目录不存在: " + tasksRoot);
            return 1;
        }
        List<Path> taskDirs;
        try (Stream<Path> stream = Files.list(tasksRoot)) {
            taskDirs = stream
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("task.yaml")))
                    .sorted()
                    .toList();
        }
        if (taskDirs.isEmpty()) {
            System.out.println("任务库为空: " + tasksRoot);
            return 0;
        }
        System.out.printf("%-24s %-12s %-8s %-10s %s%n", "TASK_ID", "TYPE", "JUDGE", "PASS", "NAME");
        for (Path dir : taskDirs) {
            try {
                TaskSpec spec = TaskSpecLoader.load(dir);
                System.out.printf("%-24s %-12s %-8s %-10s %s%n",
                        spec.taskId(),
                        spec.taskType().jsonName(),
                        spec.judge().type().jsonName(),
                        spec.scoring().passScore() + "/" + spec.scoring().maxScore(),
                        spec.taskName());
            } catch (RuntimeException e) {
                System.out.printf("%-24s %s%n", dir.getFileName(), "[INVALID] " + firstLine(e.getMessage()));
            }
        }
        return 0;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "未知错误";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
