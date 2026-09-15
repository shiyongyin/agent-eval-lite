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
    void 接入脚本内置claude_codex_custom三个profile_且与模板目录一致() throws Exception {
        Path evalset = initEvalset("preset-set");
        String script = Files.readString(evalset.resolve("scripts/run-agent.sh"));

        assertThat(script)
                .contains("claude)")
                .contains("codex)")
                .contains("custom)")
                .contains("AEL_FEEDBACK")
                .contains("AEL_AGENT_MODEL")
                .contains("AEL_AGENT_EXTRA_ARGS")
                .doesNotContain("尚未接入真实 Agent");

        // 仓库内可复制模板与 init 生成物是同一份内容，防止两处漂移。
        Path templateScript = Path.of("evalsets/_template/scripts/run-agent.sh");
        assertThat(templateScript).isRegularFile();
        assertThat(Files.readString(templateScript)).isEqualTo(script);
        assertThat(Files.readString(Path.of("evalsets/_template/agents.yaml")))
                .isEqualTo(Files.readString(evalset.resolve("agents.yaml"))
                        .replace("preset-set", "__EVALSET_ID__"));
    }

    @Test
    void 生成CI工作流模板_可解析_smoke门禁与手动触发的模型job分离_不含明文密钥() throws Exception {
        Path evalset = initEvalset("ci-set");
        Path workflow = evalset.resolve("ci/evalset-smoke.yml");
        assertThat(workflow).isRegularFile();

        String yaml = Files.readString(workflow);
        com.fasterxml.jackson.databind.JsonNode tree = com.agenteval.util.Jsons.yaml().readTree(yaml);
        assertThat(tree.path("jobs").fieldNames()).toIterable()
                .contains("smoke-scripted-baseline", "smoke-real-agents");
        // scripted 基线每个 PR 都跑；真实模型 job 只手动触发，避免 PR 上意外花钱。
        assertThat(tree.path("jobs").path("smoke-real-agents").path("if").asText())
                .contains("workflow_dispatch");
        assertThat(yaml)
                .contains("--tier smoke")
                .contains("--fail-on-not-passed")
                .contains("EVALSET_DIR: evalsets/ci-set")
                .contains("${{ secrets.")
                .doesNotContain("__EVALSET_ID__")
                .doesNotMatch("(?s).*(sk-[A-Za-z0-9]{8,}|ghp_[A-Za-z0-9]{8,}).*");

        Path templateWorkflow = Path.of("evalsets/_template/ci/evalset-smoke.yml");
        assertThat(Files.readString(templateWorkflow)).isEqualTo(yaml.replace("ci-set", "__EVALSET_ID__"));
    }

    @Test
    void 接入脚本_未知或custom_profile_明确报错退出2() throws Exception {
        Path evalset = initEvalset("err-set");
        Path inbox = Files.createDirectories(root.resolve("inbox"));
        Path instructions = Files.writeString(root.resolve("instructions.md"), "# 任务\n");

        assertThat(runScript(evalset, "custom", instructions, inbox, null, root).exit()).isEqualTo(2);
        assertThat(runScript(evalset, "nope", instructions, inbox, null, root).exit()).isEqualTo(2);
    }

    @Test
    void 接入脚本_第二轮把上一轮反馈与提交文件名注入prompt() throws Exception {
        Path evalset = initEvalset("fb-set");
        Path inbox = Files.createDirectories(root.resolve("inbox"));
        Path instructions = Files.writeString(root.resolve("instructions.md"), "# 任务说明正文\n");
        Path feedback = Files.writeString(root.resolve("attempt_001.feedback.json"), """
                {"valid":true,"feedback":"得分 35/100，未通过。","schema_errors":null,
                 "failed_checks":[{"dimension":"correctness","severity":"major","message":"总金额与订单不一致"}],
                 "dimension_scores":{"correctness":0},
                 "next_step":"请将修正后的提交写入 inbox/attempt_002.json","next_attempt_id":"attempt_002"}
                """);
        // 用桩替代真实 claude：把收到的 prompt 原样落盘，验证脚本组装行为而不依赖模型。
        Path fakeBin = Files.createDirectories(root.resolve("fakebin"));
        Path captured = root.resolve("captured-prompt.txt");
        Path fakeClaude = fakeBin.resolve("claude");
        Files.writeString(fakeClaude, "#!/usr/bin/env bash\nprintf '%s' \"$2\" > '" + captured + "'\n"
                + "printf '%s\\n' \"$@\" > '" + root.resolve("captured-args.txt") + "'\n");
        fakeClaude.toFile().setExecutable(true, false);

        ScriptResult result = runScript(evalset, "claude", instructions, inbox, feedback, fakeBin);

        assertThat(result.exit()).as(result.stderr()).isZero();
        String prompt = Files.readString(captured);
        assertThat(prompt)
                .contains("# 任务说明正文")
                .contains(inbox.toAbsolutePath() + "/attempt_002.json")
                .contains("上一轮评审反馈")
                .contains("得分 35/100，未通过。")
                .contains("总金额与订单不一致")
                .contains("inbox/attempt_002.json");
        // 只透传对外字段，不把整份 JSON（含 dimension_scores 等）原样喂给 Agent。
        assertThat(prompt).doesNotContain("dimension_scores");
        assertThat(Files.readString(root.resolve("captured-args.txt")))
                .contains("--add-dir\n" + inbox.toAbsolutePath());
    }

    private Path initEvalset(String id) {
        Path evalsetsRoot = root.resolve("evalsets");
        int exit = new CommandLine(new Main()).execute(
                "evalset", "init", "--id", id, "--evalsets-root", evalsetsRoot.toString());
        assertThat(exit).isZero();
        return evalsetsRoot.resolve(id);
    }

    private record ScriptResult(int exit, String stderr) {
    }

    private ScriptResult runScript(Path evalset, String profile, Path instructions, Path inbox,
                                   Path feedback, Path extraPathDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", evalset.resolve("scripts/run-agent.sh").toString(), profile)
                .directory(root.toFile())
                .redirectErrorStream(true);
        pb.environment().put("PATH", extraPathDir.toAbsolutePath() + ":" + System.getenv("PATH"));
        pb.environment().put("AEL_RUN_DIR", root.toString());
        pb.environment().put("AEL_INSTRUCTIONS", instructions.toString());
        pb.environment().put("AEL_WORKSPACE", root.toString());
        pb.environment().put("AEL_INBOX", inbox.toString());
        pb.environment().put("AEL_ATTEMPT_ID", "attempt_002");
        pb.environment().put("AEL_FEEDBACK", feedback == null ? "" : feedback.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        return new ScriptResult(p.waitFor(), out);
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
