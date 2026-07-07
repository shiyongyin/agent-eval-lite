package com.agenteval.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.runner.SuiteRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code evalset init} 脚手架端到端回归：生成的小团队私有测评集骨架必须可读、可解析、
 * 不覆盖已有目录，并给后续 {@code task init} / {@code suite --agents-file} 留出正确接线点。
 */
class EvalsetInitScaffoldTest {

    @TempDir
    Path root;

    @Test
    void 生成私有测评集骨架_并且agentsYaml可解析() throws Exception {
        Path evalsetsRoot = root.resolve("evalsets");

        int exit = new CommandLine(new Main()).execute(
                "evalset", "init", "--id", "ops-agent", "--evalsets-root", evalsetsRoot.toString());

        assertThat(exit).isZero();
        Path evalset = evalsetsRoot.resolve("ops-agent");
        assertThat(evalset.resolve("README.md")).isRegularFile();
        assertThat(evalset.resolve("agents.yaml")).isRegularFile();
        assertThat(evalset.resolve("scripts/run-agent.sh")).isRegularFile();
        assertThat(evalset.resolve("scripts/run-agent.sh")).isExecutable();
        assertThat(evalset.resolve("tasks/.gitkeep")).isRegularFile();

        String readme = Files.readString(evalset.resolve("README.md"));
        assertThat(readme)
                .contains("bin/agent-eval task init --id first-task-001")
                .contains("docs/07-任务质量清单.md")
                .doesNotContain("__EVALSET_ID__");

        List<SuiteRunner.AgentSpec> agents = SuiteCommand.parseAgentsFile(evalset.resolve("agents.yaml"));
        assertThat(agents).extracting(SuiteRunner.AgentSpec::label)
                .containsExactly("baseline-scripted", "current", "candidate");
    }

    @Test
    void 目录已存在_拒绝覆盖() throws Exception {
        Path evalsetsRoot = root.resolve("evalsets");
        Files.createDirectories(evalsetsRoot.resolve("dup-set"));

        int exit = new CommandLine(new Main()).execute(
                "evalset", "init", "--id", "dup-set", "--evalsets-root", evalsetsRoot.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(evalsetsRoot.resolve("dup-set/README.md")).doesNotExist();
    }

    @Test
    void 非法测评集id_拒绝生成() {
        Path evalsetsRoot = root.resolve("evalsets");
        for (String badId : new String[] {"BadCase", "has_underscore", "with/slash", "-lead"}) {
            int exit = new CommandLine(new Main()).execute(
                    "evalset", "init", "--id", badId, "--evalsets-root", evalsetsRoot.toString());
            assertThat(exit).as("id %s 应被拒绝", badId).isEqualTo(1);
        }
        assertThat(evalsetsRoot).doesNotExist();
    }
}
