package com.agenteval.judge;

import com.agenteval.submission.SubmissionManager;
import com.agenteval.trace.TraceLogger;
import com.agenteval.trace.TraceSigner;
import com.agenteval.util.Dirs;
import com.agenteval.util.Hashes;
import com.agenteval.util.JsonPaths;
import com.agenteval.util.Jsons;
import com.agenteval.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 确定性规则评审引擎：执行 {@code hidden/judge.rules.yaml} 中的全部检查项。
 *
 * <p>全部检查类型都是确定性的（同输入必同输出）——这是「能用规则就不用 LLM」原则的落点。
 * {@code command} 型检查在 <strong>workspace 的临时副本</strong>上执行（用后即焚），
 * 等价于 EdgeBench ephemeral judge container 的本地目录版：评审动作永不污染 Agent 的工作区。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class RulesJudge {

    private static final Logger log = LoggerFactory.getLogger(RulesJudge.class);
    private static final int MAX_SCANNED_FILE_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RulesJudge() {
    }

    /**
     * 执行规则文件中的全部检查项。
     *
     * @param rules 规则文件模型
     * @param input 评审输入
     * @return 逐检查项结论（与规则文件声明顺序一致）
     */
    public static List<CheckOutcome> run(RulesFile rules, JudgeInput input) {
        Path ephemeralWorkspace = createEphemeralWorkspace(input);
        try {
            List<CheckOutcome> outcomes = new ArrayList<>();
            for (RulesFile.CheckDef def : rules.checks()) {
                outcomes.add(runCheck(def, rules, input, ephemeralWorkspace));
            }
            return outcomes;
        } finally {
            Dirs.deleteTree(ephemeralWorkspace);
        }
    }

    private static CheckOutcome runCheck(
            RulesFile.CheckDef def, RulesFile rules, JudgeInput input, Path ephemeralWorkspace) {
        try {
            return switch (def.type()) {
                case "json_schema" -> jsonSchema(def, input);
                case "jsonpath_equals" -> jsonpathEquals(def, input);
                case "jsonpath_exists" -> jsonpathExists(def, input);
                case "jsonpath_matches" -> jsonpathMatches(def, input);
                case "list_coverage" -> listCoverage(def, input);
                case "evidence_sources_valid" -> evidenceSourcesValid(def, input);
                case "workspace_file_exists" -> workspaceFileExists(def, input);
                case "workspace_file_contains" -> workspaceFileContains(def, input);
                case "changed_files_verified" -> changedFilesVerified(def, input);
                case "command" -> command(def, input, ephemeralWorkspace);
                case "tool_call_required" -> toolCallRequired(def, input);
                case "tool_call_forbidden" -> toolCallForbidden(def, input);
                case "world_state" -> worldState(def, input);
                case "no_canary_leak" -> noCanaryLeak(def, rules, input);
                default -> throw new JudgeException("未知 check 类型: " + def.type());
            };
        } catch (JudgeException e) {
            throw e;
        } catch (Exception e) {
            // 单个检查项的意外故障按评审设施故障处理，禁止悄悄折算成 Agent 的分数。
            throw new JudgeException("check " + def.id() + " 执行故障: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- checks

    private static CheckOutcome jsonSchema(RulesFile.CheckDef def, JudgeInput input) {
        String target = def.raw().path("target").asText("$");
        JsonNode node = JsonPaths.resolve(input.submission(), target);
        if (node.isMissingNode()) {
            return CheckOutcome.fail(def, "目标节点不存在: " + target);
        }
        Path schemaFile = input.hiddenDir().resolve(requiredParam(def, "schema_file"));
        List<String> errors = SubmissionManager.validateAgainst(
                SubmissionManager.schemaFromFile(schemaFile), node);
        return errors.isEmpty()
                ? CheckOutcome.pass(def, "schema 校验通过")
                : CheckOutcome.fail(def, "schema 校验失败: " + String.join("; ", errors));
    }

    private static CheckOutcome jsonpathEquals(RulesFile.CheckDef def, JudgeInput input) {
        String path = requiredParam(def, "path");
        JsonNode actual = JsonPaths.resolve(input.submission(), path);
        if (actual.isMissingNode()) {
            return CheckOutcome.fail(def, "路径不存在: " + path);
        }
        JsonNode expected = resolveExpected(def, input);
        double tolerance = def.raw().path("tolerance").asDouble(0);
        boolean equal;
        if (actual.isNumber() && expected.isNumber() && tolerance > 0) {
            equal = Math.abs(actual.doubleValue() - expected.doubleValue()) <= tolerance;
        } else {
            equal = actual.equals(expected);
        }
        // 诊断信息只进 private_notes，expected 值绝不进入对外文案。
        return equal
                ? CheckOutcome.pass(def, "实际值与期望一致")
                : CheckOutcome.fail(def, "不相等: 实际=" + brief(actual) + " 期望=" + brief(expected));
    }

    private static CheckOutcome jsonpathExists(RulesFile.CheckDef def, JudgeInput input) {
        String path = requiredParam(def, "path");
        JsonNode node = JsonPaths.resolve(input.submission(), path);
        return node.isMissingNode() || node.isNull()
                ? CheckOutcome.fail(def, "路径不存在或为 null: " + path)
                : CheckOutcome.pass(def, "路径存在: " + path);
    }

    private static CheckOutcome jsonpathMatches(RulesFile.CheckDef def, JudgeInput input) {
        String path = requiredParam(def, "path");
        String regex = requiredParam(def, "regex");
        JsonNode node = JsonPaths.resolve(input.submission(), path);
        if (node.isMissingNode()) {
            return CheckOutcome.fail(def, "路径不存在: " + path);
        }
        String text = node.isTextual() ? node.asText() : node.toString();
        boolean matched = Pattern.compile(regex, Pattern.DOTALL).matcher(text).find();
        return matched
                ? CheckOutcome.pass(def, "正则命中: " + regex)
                : CheckOutcome.fail(def, "正则未命中: " + regex);
    }

    private static CheckOutcome listCoverage(RulesFile.CheckDef def, JudgeInput input) {
        String path = def.raw().path("path").asText("$");
        JsonNode node = JsonPaths.resolve(input.submission(), path);
        if (node.isMissingNode()) {
            return CheckOutcome.fail(def, "路径不存在: " + path);
        }
        String haystack = flattenText(node).toLowerCase(Locale.ROOT);

        JsonNode groups = def.raw().path("expected_any_of");
        if (!groups.isArray() || groups.isEmpty()) {
            throw new JudgeException("check " + def.id() + " 缺少 expected_any_of");
        }
        int total = groups.size();
        int minMatches = def.raw().path("min_matches").asInt(total);
        List<String> matchedGroups = new ArrayList<>();
        List<String> missedGroups = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            JsonNode group = groups.get(i);
            boolean hit = false;
            for (JsonNode keyword : group) {
                if (haystack.contains(keyword.asText().toLowerCase(Locale.ROOT))) {
                    hit = true;
                    break;
                }
            }
            String label = "关键点#" + (i + 1);
            if (hit) {
                matchedGroups.add(label);
            } else {
                missedGroups.add(label + group);
            }
        }
        int matched = matchedGroups.size();
        double earned = round2(def.points() * matched / total);
        boolean passed = matched >= minMatches;
        return CheckOutcome.partial(def, earned, passed,
                "覆盖 " + matched + "/" + total + "（门槛 " + minMatches + "）；未命中: " + missedGroups);
    }

    private static CheckOutcome evidenceSourcesValid(RulesFile.CheckDef def, JudgeInput input) {
        Set<String> visible = new HashSet<>(input.taskSpec().visibleContext());
        List<String> cited = new ArrayList<>();
        input.submission().path("evidence").forEach(item -> {
            if ("file".equals(item.path("type").asText())) {
                cited.add(item.path("source").asText());
            }
        });
        input.submission().path("sources").forEach(item -> cited.add(item.asText()));

        List<String> invalid = new ArrayList<>();
        for (String source : cited) {
            boolean inVisibleList = visible.contains(source);
            boolean existsInWorkspace = source.startsWith("work/")
                    && Files.isRegularFile(input.workspaceDir().resolve(source.substring("work/".length())));
            if (!inVisibleList && !existsInWorkspace) {
                invalid.add(source);
            }
        }
        if (cited.isEmpty()) {
            return CheckOutcome.fail(def, "提交未包含任何文件引用");
        }
        return invalid.isEmpty()
                ? CheckOutcome.pass(def, "全部 " + cited.size() + " 条引用有效")
                : CheckOutcome.fail(def, "无效引用: " + invalid);
    }

    private static CheckOutcome workspaceFileExists(RulesFile.CheckDef def, JudgeInput input) {
        String rel = requiredParam(def, "path");
        return Files.isRegularFile(input.workspaceDir().resolve(rel))
                ? CheckOutcome.pass(def, "文件存在: " + rel)
                : CheckOutcome.fail(def, "文件不存在: " + rel);
    }

    private static CheckOutcome workspaceFileContains(RulesFile.CheckDef def, JudgeInput input) {
        String rel = requiredParam(def, "path");
        String pattern = requiredParam(def, "pattern");
        Path file = input.workspaceDir().resolve(rel);
        if (!Files.isRegularFile(file)) {
            return CheckOutcome.fail(def, "文件不存在: " + rel);
        }
        String content = readText(file);
        boolean matched = Pattern.compile(pattern, Pattern.DOTALL).matcher(content).find();
        return matched
                ? CheckOutcome.pass(def, "文件内容命中: " + pattern)
                : CheckOutcome.fail(def, "文件内容未命中: " + pattern);
    }

    private static CheckOutcome changedFilesVerified(RulesFile.CheckDef def, JudgeInput input) {
        if (input.baselineFile() == null) {
            return CheckOutcome.fail(def, "缺少 workspace 基线，无法核验 changed_files（离线判分需 --run 上下文）");
        }
        Map<String, String> baseline = WorkspaceManager.readBaseline(input.baselineFile());
        if (baseline == null) {
            return CheckOutcome.fail(def, "基线文件不存在: " + input.baselineFile());
        }
        Map<String, String> current = WorkspaceManager.fileBaseline(input.workspaceDir());

        List<String> declared = new ArrayList<>();
        input.submission().path("changed_files")
                .forEach(item -> declared.add(item.path("path").asText()));
        if (declared.isEmpty()) {
            return CheckOutcome.fail(def, "changed_files 为空");
        }

        List<String> unchanged = new ArrayList<>();
        for (String path : declared) {
            String before = baseline.get(path);
            String after = current.get(path);
            boolean reallyChanged = (before == null && after != null)   // 新增
                    || (before != null && after == null)                 // 删除
                    || (before != null && !before.equals(after));        // 修改
            if (!reallyChanged) {
                unchanged.add(path);
            }
        }
        if (!unchanged.isEmpty()) {
            return CheckOutcome.fail(def, "申报了修改但内容与基线一致: " + unchanged);
        }

        if (def.raw().path("require_all_changes_declared").asBoolean(false)) {
            Set<String> actualChanged = new TreeSet<>();
            Set<String> union = new LinkedHashSet<>(baseline.keySet());
            union.addAll(current.keySet());
            for (String path : union) {
                if (!java.util.Objects.equals(baseline.get(path), current.get(path))) {
                    actualChanged.add(path);
                }
            }
            actualChanged.removeAll(new HashSet<>(declared));
            if (!actualChanged.isEmpty()) {
                return CheckOutcome.fail(def, "存在未申报的实际修改: " + actualChanged);
            }
        }
        return CheckOutcome.pass(def, "申报的 " + declared.size() + " 处修改均已核验为真实变更");
    }

    private static CheckOutcome command(RulesFile.CheckDef def, JudgeInput input, Path ephemeralWorkspace) {
        String cmdTemplate = requiredParam(def, "cmd");
        int timeoutSeconds = def.raw().path("timeout_seconds").asInt(60);
        int expectExitCode = def.raw().path("expect_exit_code").asInt(0);
        String outputRegex = def.raw().path("output_regex").asText(null);

        // 每次判分生成随机挑战串：命令模板与 output_regex 中的 {nonce} 均替换为它。
        // 受信任的隐藏 harness 只在完成真实断言后回显含 nonce 的成功标记；Agent 事先写死的
        // 成功令牌（如静态块抢先打印固定串再 System.exit）因不含本轮 nonce 而无法命中（红队 P0-3）。
        String nonce = HexFormat.of().formatHex(nonceBytes());

        Path submissionCopy = ephemeralWorkspace.resolve(".ael-submission.json");
        try {
            Files.writeString(submissionCopy, input.submission().toPrettyString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JudgeException("写入提交副本失败", e);
        }
        String cmd = cmdTemplate
                .replace("{hidden}", input.hiddenDir().toAbsolutePath().toString())
                .replace("{workspace}", ephemeralWorkspace.toAbsolutePath().toString())
                .replace("{submission}", submissionCopy.toAbsolutePath().toString())
                .replace("{task}", input.taskDir().toAbsolutePath().toString())
                .replace("{nonce}", nonce);
        if (outputRegex != null) {
            outputRegex = outputRegex.replace("{nonce}", nonce);
        }

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", cmd)
                .directory(ephemeralWorkspace.toFile())
                .redirectErrorStream(true);
        builder.environment().put("AEL_HIDDEN", input.hiddenDir().toAbsolutePath().toString());
        builder.environment().put("AEL_WORKSPACE", ephemeralWorkspace.toAbsolutePath().toString());
        builder.environment().put("AEL_SUBMISSION", submissionCopy.toAbsolutePath().toString());

        long start = System.nanoTime();
        String output;
        int exitCode;
        try {
            Process process = builder.start();
            byte[] outputBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return CheckOutcome.fail(def, "命令超时（" + timeoutSeconds + "s）: " + cmd);
            }
            exitCode = process.exitValue();
            output = new String(outputBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JudgeException("命令启动失败: " + cmd, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgeException("命令执行被中断: " + cmd, e);
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        persistCommandLog(def, input, cmd, exitCode, durationMs, output);

        if (exitCode != expectExitCode) {
            return CheckOutcome.fail(def, "退出码 " + exitCode + "（期望 " + expectExitCode + "）；输出尾部: " + tail(output, 800));
        }
        if (outputRegex != null && !outputRegex.isBlank()
                && !Pattern.compile(outputRegex, Pattern.DOTALL).matcher(output).find()) {
            return CheckOutcome.fail(def, "输出未命中正则 " + outputRegex + "；输出尾部: " + tail(output, 800));
        }
        return CheckOutcome.pass(def, "命令通过（" + durationMs + "ms）");
    }

    private static CheckOutcome toolCallRequired(RulesFile.CheckDef def, JudgeInput input) {
        String tool = requiredParam(def, "tool");
        int minCalls = def.raw().path("min_calls").asInt(1);
        boolean requireReferenced = def.raw().path("require_referenced").asBoolean(true);

        List<String> successCallIds = successfulToolCallIds(input, tool);
        if (successCallIds.size() < minCalls) {
            return CheckOutcome.fail(def, "工具 " + tool + " 成功调用 " + successCallIds.size()
                    + " 次，少于要求的 " + minCalls + " 次");
        }
        if (requireReferenced) {
            Set<String> referenced = new HashSet<>();
            input.submission().path("tool_calls_used").forEach(item -> {
                if (tool.equals(item.path("tool_name").asText())) {
                    referenced.add(item.path("call_id").asText());
                }
            });
            boolean anyReal = successCallIds.stream().anyMatch(referenced::contains);
            if (!anyReal) {
                return CheckOutcome.fail(def, "提交引用的 call_id 在调用记录中不存在（疑似编造）: "
                        + referenced + " vs 真实 " + successCallIds);
            }
        }
        return CheckOutcome.pass(def, "工具 " + tool + " 真实调用 " + successCallIds.size() + " 次且引用可核验");
    }

    private static CheckOutcome toolCallForbidden(RulesFile.CheckDef def, JudgeInput input) {
        String tool = requiredParam(def, "tool");
        List<String> callIds = successfulToolCallIds(input, tool);
        return callIds.isEmpty()
                ? CheckOutcome.pass(def, "未调用禁用工具 " + tool)
                : CheckOutcome.fail(def, "调用了禁用工具 " + tool + ": " + callIds);
    }

    /**
     * 终态比对（借 tau-bench「比世界终态而非比嘴」思想）：把 trace 中<strong>签名可核验且成功</strong>的
     * 写工具调用按发生顺序折叠成「世界终态」，与隐藏期望终态整体比对。
     *
     * <p>为什么折叠 trace 而不是另存状态文件：状态文件是新的可篡改面，而 tool_call 事件带
     * per-run HMAC 签名（红队 P0-2 已验证不可伪造）——由可信事件重放出的终态天然可信。
     * 提交里怎么说不重要，工具真正写进世界的是什么才算数；「过程对、终态错」（调用顺序合规
     * 但入参写错）在此被拦下。
     *
     * <p>配置：{@code tools}（构成终态的写工具名列表）、{@code expected} / {@code expected_from}
     * （期望终态，元素形如 {@code {tool, input}}）、{@code order_sensitive}（默认 false，
     * 多重集比对；true 时要求逐位相等）、{@code scope}（默认 {@code attempt}：只折叠本轮写操作，
     * 与「失败→反馈→重试」循环自洽——上一轮写错不永久毒化后续轮次；{@code run} 折叠全 run）。
     */
    private static CheckOutcome worldState(RulesFile.CheckDef def, JudgeInput input) {
        JsonNode toolsNode = def.raw().path("tools");
        if (!toolsNode.isArray() || toolsNode.isEmpty()) {
            throw new JudgeException("check " + def.id() + " 缺少 tools（构成世界终态的写工具列表）");
        }
        Set<String> tools = new LinkedHashSet<>();
        toolsNode.forEach(t -> tools.add(t.asText()));
        String scope = def.raw().path("scope").asText("attempt");
        if (!"attempt".equals(scope) && !"run".equals(scope)) {
            throw new JudgeException("check " + def.id() + " 的 scope 非法: " + scope + "（可选 attempt / run）");
        }

        // 实际终态：按 trace 顺序折叠可信且成功的写调用（元素 = {tool, input}）。
        String attemptFilter = "attempt".equals(scope) ? input.attemptId() : null;
        List<JsonNode> actual = new ArrayList<>();
        for (JsonNode payload : verifiedSuccessfulCalls(input, tools, attemptFilter)) {
            var element = Jsons.json().createObjectNode();
            element.put("tool", payload.path("tool_name").asText());
            element.set("input", payload.path("input").deepCopy());
            actual.add(element);
        }

        JsonNode expectedNode = resolveExpected(def, input);
        if (!expectedNode.isArray()) {
            throw new JudgeException("check " + def.id() + " 的期望终态必须是数组（元素形如 {tool, input}）");
        }
        List<JsonNode> expected = new ArrayList<>();
        expectedNode.forEach(expected::add);

        boolean orderSensitive = def.raw().path("order_sensitive").asBoolean(false);
        List<String> problems = orderSensitive
                ? compareOrdered(actual, expected)
                : compareAsMultiset(actual, expected);

        // 诊断细节只进 private_notes；对外文案走 feedback_fail，绝不回传期望终态。
        return problems.isEmpty()
                ? CheckOutcome.pass(def, "世界终态与期望一致（" + actual.size() + " 次写操作）")
                : CheckOutcome.fail(def, "终态不一致: " + String.join("; ", problems));
    }

    private static List<String> compareOrdered(List<JsonNode> actual, List<JsonNode> expected) {
        List<String> problems = new ArrayList<>();
        if (actual.size() != expected.size()) {
            problems.add("写操作次数 " + actual.size() + "（期望 " + expected.size() + "）");
        }
        int comparable = Math.min(actual.size(), expected.size());
        for (int i = 0; i < comparable; i++) {
            if (!actual.get(i).equals(expected.get(i))) {
                problems.add("第 " + (i + 1) + " 次写不符: 实际=" + brief(actual.get(i))
                        + " 期望=" + brief(expected.get(i)));
            }
        }
        return problems;
    }

    private static List<String> compareAsMultiset(List<JsonNode> actual, List<JsonNode> expected) {
        List<String> problems = new ArrayList<>();
        List<JsonNode> unmatched = new ArrayList<>(expected);
        List<JsonNode> extra = new ArrayList<>();
        for (JsonNode item : actual) {
            // JsonNode.equals 是深度相等（对象字段无序、数组有序），适合作为「同一条写操作」的判据。
            // 多重集语义：每条实际写操作只消耗一条期望（重复写同一条也会被识别为多余）。
            int matchIndex = -1;
            for (int i = 0; i < unmatched.size(); i++) {
                if (unmatched.get(i).equals(item)) {
                    matchIndex = i;
                    break;
                }
            }
            if (matchIndex >= 0) {
                unmatched.remove(matchIndex);
            } else {
                extra.add(item);
            }
        }
        for (JsonNode miss : unmatched) {
            problems.add("缺少期望写操作: " + brief(miss));
        }
        for (JsonNode item : extra) {
            problems.add("多出非期望写操作: " + brief(item));
        }
        return problems;
    }

    private static CheckOutcome noCanaryLeak(RulesFile.CheckDef def, RulesFile rules, JudgeInput input) {
        String token = def.raw().path("token").asText(rules.canaryToken());
        if (token == null || token.isBlank()) {
            return CheckOutcome.pass(def, "未配置 canary token，跳过");
        }
        List<String> leaks = new ArrayList<>();
        if (input.submission().toString().contains(token)) {
            leaks.add("submission");
        }
        scanTreeForToken(input.workspaceDir(), input.workspaceDir(), "workspace", token, leaks);

        // 扩大扫描面：Agent 若把 hidden 内容 dump 到自己的 stdout，会落进 agent-logs；
        // 提交/中间文件落进 inbox。真实 run 里这两个目录是 workspace 的同级目录，
        // 覆盖它们能抓住「读取后打印/转存」这一最常见的越界泄露形态（红队 P0-1 缓解）。
        // 同时扫 traces/：Agent 若把偷到的 canary 当作工具入参经网关外泄（gateway 会把 input
        // 原样记入 append-only trace），仅扫 workspace/日志会漏检——覆盖 trace 才能封住这条
        // 「借合法工具调用夹带隐藏数据」的 exfiltration 通道（红队 P0-1 扩展）。
        if ("workspace".equals(fileName(input.workspaceDir()))) {
            Path runDir = input.workspaceDir().getParent();
            if (runDir != null) {
                scanTreeForToken(runDir.resolve("agent-logs"), runDir, "agent-logs", token, leaks);
                scanTreeForToken(runDir.resolve("inbox"), runDir, "inbox", token, leaks);
                scanTreeForToken(runDir.resolve("traces"), runDir, "traces", token, leaks);
            }
        }
        return leaks.isEmpty()
                ? CheckOutcome.pass(def, "未发现 hidden 泄露痕迹")
                : CheckOutcome.fail(def, "发现 canary 泄露（越界读取 hidden 的确凿证据）: " + leaks);
    }

    private static void scanTreeForToken(Path root, Path relativeTo, String label,
                                         String token, List<String> leaks) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    if (Files.size(file) <= MAX_SCANNED_FILE_BYTES && readText(file).contains(token)) {
                        leaks.add(label + "/" + relativeTo.relativize(file));
                    }
                } catch (IOException e) {
                    log.warn("canary 扫描跳过不可读文件: {}", file);
                }
            });
        } catch (IOException e) {
            throw new JudgeException("canary 扫描失败: " + root, e);
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    // ---------------------------------------------------------------- helper

    private static Path createEphemeralWorkspace(JudgeInput input) {
        try {
            Path temp = Files.createTempDirectory("ael-judge-");
            if (Files.isDirectory(input.workspaceDir())) {
                Dirs.copyTree(input.workspaceDir(), temp);
            }
            return temp;
        } catch (IOException e) {
            throw new JudgeException("创建评审临时工作区失败", e);
        }
    }

    private static List<String> successfulToolCallIds(JudgeInput input, String tool) {
        return verifiedSuccessfulCalls(input, Set.of(tool), null).stream()
                .map(payload -> payload.path("call_id").asText())
                .toList();
    }

    /**
     * 提取 trace 中指定工具的<strong>成功</strong>调用负载，按发生顺序返回。
     *
     * <p>有密钥则只认可核验签名的事件——伪造/无签名的 tool_call 直接丢弃（红队 P0-2）；
     * 无密钥（离线复核）退化为统计全部事件的历史语义。
     *
     * @param input 评审输入
     * @param tools 关注的工具名集合
     * @param attemptId 只保留该 attempt 的事件；{@code null} 表示全 run
     * @return 成功调用的 payload 节点列表（含 call_id / tool_name / input）
     */
    private static List<JsonNode> verifiedSuccessfulCalls(JudgeInput input, Set<String> tools,
                                                          String attemptId) {
        if (input.traceFile() == null) {
            return List.of();
        }
        byte[] secret = input.traceSecret();
        return TraceLogger.readAll(input.traceFile()).stream()
                .filter(event -> "tool_call".equals(event.path("type").asText()))
                .filter(event -> secret == null || TraceSigner.verify(secret, event))
                .filter(event -> attemptId == null || attemptId.equals(event.path("attempt_id").asText()))
                .filter(event -> tools.contains(event.path("payload").path("tool_name").asText()))
                .filter(event -> event.path("payload").path("success").asBoolean(false))
                .map(event -> event.path("payload"))
                .toList();
    }

    private static JsonNode resolveExpected(RulesFile.CheckDef def, JudgeInput input) {
        if (def.raw().has("expected")) {
            return def.raw().get("expected");
        }
        String expectedFrom = def.raw().path("expected_from").asText("");
        if (expectedFrom.isBlank()) {
            throw new JudgeException("check " + def.id() + " 缺少 expected 或 expected_from");
        }
        String[] parts = expectedFrom.split("#", 2);
        Path file = input.hiddenDir().resolve(parts[0]);
        try {
            JsonNode root = Jsons.json().readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode node = parts.length > 1 ? root.at(parts[1]) : root;
            if (node.isMissingNode()) {
                throw new JudgeException("expected_from 指针不存在: " + expectedFrom);
            }
            return node;
        } catch (IOException e) {
            throw new JudgeException("expected_from 文件读取失败: " + expectedFrom, e);
        }
    }

    private static void persistCommandLog(RulesFile.CheckDef def, JudgeInput input,
                                          String cmd, int exitCode, long durationMs, String output) {
        if (input.judgeOutputDir() == null) {
            return;
        }
        try {
            Files.createDirectories(input.judgeOutputDir());
            Path logFile = input.judgeOutputDir()
                    .resolve(input.attemptId() + ".cmd." + def.id() + ".log");
            Files.writeString(logFile,
                    "$ " + cmd + "\n(exit=" + exitCode + ", " + durationMs + "ms)\n\n" + output,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("命令日志写入失败（不影响评分）: {}", e.getMessage());
        }
    }

    private static String flattenText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(item -> parts.add(flattenText(item)));
            return String.join("\n", parts);
        }
        return node.toString();
    }

    private static String requiredParam(RulesFile.CheckDef def, String param) {
        String value = def.raw().path(param).asText("");
        if (value.isBlank()) {
            throw new JudgeException("check " + def.id() + " 缺少参数 " + param);
        }
        return value;
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + file, e);
        }
    }

    private static String brief(JsonNode node) {
        String text = node.toString();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    private static String tail(String text, int maxChars) {
        String trimmed = text.strip();
        return trimmed.length() <= maxChars ? trimmed : "…" + trimmed.substring(trimmed.length() - maxChars);
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static byte[] nonceBytes() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * 计算 hidden 目录指纹（进入 reproducibility）。
     *
     * @param input 评审输入
     * @return hidden 目录树 SHA-256
     */
    public static String hiddenFingerprint(JudgeInput input) {
        return Hashes.sha256OfDir(input.hiddenDir());
    }

    /**
     * 汇总内部诊断信息为 private_notes 文本。
     *
     * @param outcomes 全部检查结论
     * @return 逐项「id: 诊断」拼接文本
     */
    public static String privateNotes(List<CheckOutcome> outcomes) {
        return outcomes.stream()
                .map(o -> o.id() + " [" + (o.passed() ? "PASS" : "FAIL") + " "
                        + o.pointsEarned() + "/" + o.pointsPossible() + "] " + o.message())
                .collect(Collectors.joining("\n"));
    }
}
