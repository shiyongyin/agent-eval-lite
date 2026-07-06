package com.agenteval.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenteval.runner.SuiteRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --agents-file} 多 Agent 对比配置的解析回归：合法配置解析出正确规格，
 * 非法配置（缺 cmd、类型不合法、标签重复、空列表）给出可读错误而非跑一半才崩。
 */
class SuiteCommandAgentsFileTest {

    @TempDir
    Path dir;

    @Test
    void 合法配置_解析出标签与类型正确的规格列表() throws Exception {
        Path file = write("""
                agents:
                  - label: baseline
                    type: scripted
                  - label: my-agent
                    type: cli
                    cmd: 'bash my_agent.sh'
                """);

        List<SuiteRunner.AgentSpec> specs = SuiteCommand.parseAgentsFile(file);

        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).label()).isEqualTo("baseline");
        assertThat(specs.get(0).requiresReplay()).isTrue();
        assertThat(specs.get(1).label()).isEqualTo("my-agent");
        assertThat(specs.get(1).requiresReplay()).isFalse();
    }

    @Test
    void cli缺少cmd_报可读错误() throws Exception {
        Path file = write("""
                agents:
                  - label: broken
                    type: cli
                """);

        assertThatThrownBy(() -> SuiteCommand.parseAgentsFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 cmd");
    }

    @Test
    void 类型不合法_报可读错误() throws Exception {
        Path file = write("""
                agents:
                  - label: x
                    type: http
                """);

        assertThatThrownBy(() -> SuiteCommand.parseAgentsFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型不合法");
    }

    @Test
    void 标签重复_报可读错误() throws Exception {
        Path file = write("""
                agents:
                  - label: same
                    type: scripted
                  - label: same
                    type: cli
                    cmd: 'x'
                """);

        assertThatThrownBy(() -> SuiteCommand.parseAgentsFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签重复");
    }

    @Test
    void 空列表_报可读错误() throws Exception {
        Path file = write("agents: []\n");

        assertThatThrownBy(() -> SuiteCommand.parseAgentsFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列表为空");
    }

    private Path write(String yaml) throws Exception {
        Path file = dir.resolve("agents.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
