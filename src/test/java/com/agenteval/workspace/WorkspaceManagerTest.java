package com.agenteval.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.task.TaskContext;
import com.agenteval.task.TaskSpecLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceManager} 的目录初始化与基线指纹测试（基于真实示例任务）。
 */
class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void prepare复制work并落盘基线() throws Exception {
        Path taskDir = Path.of("tasks", "code-fix-001");
        TaskContext ctx = TaskContext.of(TaskSpecLoader.load(taskDir), taskDir, tempDir.resolve("run_t"));

        WorkspaceManager.Fingerprints fingerprints = WorkspaceManager.prepare(ctx);

        assertThat(ctx.workspaceDir().resolve("src/PriceCalculator.java")).isRegularFile();
        assertThat(ctx.inboxDir()).isDirectory();
        assertThat(ctx.feedbackDir()).isDirectory();
        assertThat(ctx.baselineFile()).isRegularFile();
        assertThat(fingerprints.hiddenFingerprint()).hasSize(64);
        assertThat(fingerprints.workspaceFingerprint()).hasSize(64);

        Map<String, String> baseline = WorkspaceManager.readBaseline(ctx.baselineFile());
        assertThat(baseline).containsKey("src/PriceCalculator.java");

        // 修改文件后基线应能反映差异。
        Files.writeString(ctx.workspaceDir().resolve("src/PriceCalculator.java"),
                "public class PriceCalculator {}", StandardCharsets.UTF_8);
        Map<String, String> current = WorkspaceManager.fileBaseline(ctx.workspaceDir());
        assertThat(current.get("src/PriceCalculator.java"))
                .isNotEqualTo(baseline.get("src/PriceCalculator.java"));
    }
}
