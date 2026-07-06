# 仓库指南

## 项目结构与模块组织

AgentEval-Lite 是 Java 17 + Maven 的命令行项目。生产代码位于 `src/main/java/com/agenteval`，按职责拆分为 `cli`、`runner`、`judge`、`task`、`submission`、`trace`、`report` 等包。JSON Schema 放在 `src/main/resources/schemas`。测试代码位于 `src/test/java`，其中端到端测试集中在 `src/test/java/com/agenteval/integration`。

任务样例位于 `tasks/<task-id>/`，标准结构包含 `task.yaml`、Agent 可见的 `work/`、评审私有的 `hidden/` 和 `samples/`。`target/` 与 `runs/` 是生成目录，已在 `.gitignore` 中忽略，不应提交。

## 构建、测试与本地开发命令

- `mvn -q test`：运行 JUnit 5 + AssertJ 测试套件。
- `mvn -q package`：运行测试并在 `target/` 生成 shaded CLI jar。
- `bin/agent-eval list`：打包后列出内置评估任务。
- `bin/agent-eval validate --task tasks/api-payload-001`：校验单个任务的结构与规则引用。
- `bin/agent-eval run --task tasks/api-payload-001 --agent scripted --script tasks/api-payload-001/samples/replay.yaml`：运行确定性的示例评估。

## 编码风格与命名约定

使用 Java 17、UTF-8 和四空格缩进。包名保持小写；类名和 record 使用 `PascalCase`；方法、字段和局部变量使用 `camelCase`。偏向使用不可变 record 或 `final` 类表达值对象。公开 API 按现有风格补充 Javadoc，核心类型保留 `@author` 与 `@since`。

任务 ID 和任务目录使用 kebab-case 加数字后缀，例如 `code-fix-001`。YAML 字段使用 snake_case，与现有 `task.yaml` 保持一致。

## 测试指南

单元测试命名为 `*Test.java`，放在 `src/test/java` 下与被测包对应的位置。集成测试放入 `integration` 包。新增任务样例时，应提供 `samples/attempt-pass.json`、`samples/attempt-fail.json` 和 `samples/replay.yaml`，并运行 `mvn -q test` 与 `bin/agent-eval validate --task tasks/<task-id>`。

## 提交与 Pull Request 规范

当前仓库没有 Git 提交历史，因此尚无项目内既定提交规范。形成规范前，使用简短祈使句作为提交标题，例如 `Add task validation checks`。

Pull Request 应说明变更内容、列出已运行的验证命令，并明确指出任务样例或 Schema 的改动。不要在 PR 描述中泄露 `hidden/expected` 答案或私有评审细节。

## 安全与配置提示

评审专用数据必须保存在 `tasks/<task-id>/hidden/`。不要把 expected 答案复制到 `work/`、公开文档、面向 Agent 的 samples 或生成报告中。
