package com.agenteval.cli;

import com.agenteval.judge.RulesFile;
import com.agenteval.task.JudgeType;
import com.agenteval.task.TaskSpec;
import com.agenteval.task.TaskSpecException;
import com.agenteval.task.TaskSpecLoader;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code agent-eval validate}：任务规格静态体检（结构 + 引用 + 规则文件），
 * 供任务作者在提交任务前自查，也作为 CI 的任务库门禁。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
@Command(name = "validate", mixinStandardHelpOptions = true, description = "校验任务定义是否合法完整")
public final class ValidateCommand implements Callable<Integer> {

    @Option(names = "--task", required = true, description = "任务目录")
    private Path taskDir;

    @Override
    public Integer call() {
        TaskSpec spec;
        try {
            spec = TaskSpecLoader.load(taskDir);
        } catch (TaskSpecException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        int checkCount = 0;
        if (spec.judge().type() == JudgeType.RULES || spec.judge().type() == JudgeType.HYBRID) {
            RulesFile rules = RulesFile.load(
                    taskDir.resolve(spec.judge().rulesFile()),
                    spec.scoring().dimensions().stream()
                            .map(TaskSpec.Dimension::name)
                            .collect(Collectors.toSet()));
            checkCount = rules.checks().size();
        }
        System.out.println("OK  " + spec.taskId()
                + "（" + spec.taskType().jsonName()
                + "，judge=" + spec.judge().type().jsonName()
                + "，checks=" + checkCount
                + "，维度=" + spec.scoring().dimensions().size()
                + "，通过线=" + spec.scoring().passScore() + "/" + spec.scoring().maxScore() + "）");
        return 0;
    }
}
