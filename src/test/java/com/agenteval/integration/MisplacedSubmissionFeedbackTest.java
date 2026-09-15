package com.agenteval.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.agent.CliAgentAdapter;
import com.agenteval.runner.RunManager;
import com.agenteval.state.RunStatus;
import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dogfooding 实测的真实失误模式：Agent 把提交写到了 {@code workspace/inbox/attempt_001.json}（按 cwd 相对解析），
 * 自己还宣称"已提交"。框架不能把这种文件当提交（唯一通道不变），但反馈必须指出"文件写错了位置、正确的绝对路径是什么"，
 * 让下一轮能修正，而不是只说"没找到文件"。
 */
class MisplacedSubmissionFeedbackTest {

    @TempDir
    Path runsRoot;

    @Test
    void 提交写到workspace下的同名路径_不计分_但反馈点名错位文件与正确绝对路径() throws Exception {
        Path sample = Path.of("tasks/api-payload-001/samples/attempt-pass.json").toAbsolutePath();
        // 第 1 轮写错位置（workspace/inbox/），第 2 轮起写对。
        String cmd = "if [ \"$AEL_ATTEMPT_ID\" = attempt_001 ]; then mkdir -p \"$AEL_WORKSPACE/inbox\"; "
                + "target=\"$AEL_WORKSPACE/inbox/$AEL_ATTEMPT_ID.json\"; else target=\"$AEL_INBOX/$AEL_ATTEMPT_ID.json\"; fi; "
                + "sed \"s/[{]attempt_id[}]/${AEL_ATTEMPT_ID}/\" '" + sample + "' > \"$target\"";

        RunManager.RunOutcome outcome = RunManager.run(Path.of("tasks/api-payload-001"), runsRoot,
                null, "misplace-test", new CliAgentAdapter(cmd));

        assertThat(outcome.status()).isEqualTo(RunStatus.PASSED);
        assertThat(outcome.bestAttemptId()).isEqualTo("attempt_002");

        JsonNode fb = Jsons.json().readTree(Files.readString(
                outcome.runDir().resolve("feedback/attempt_001.feedback.json")));
        assertThat(fb.path("valid").asBoolean()).isFalse();
        String errors = fb.path("schema_errors").toString();
        Path misplaced = outcome.runDir().resolve("workspace/inbox/attempt_001.json").toAbsolutePath();
        Path inbox = outcome.runDir().resolve("inbox").toAbsolutePath();
        assertThat(errors).contains(misplaced.toString()).contains(inbox.toString());
        assertThat(fb.path("next_step").asText()).contains(inbox.resolve("attempt_002.json").toString());
    }
}
