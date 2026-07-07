#!/usr/bin/env bash
# 生成 docs/CODEMAP.md —— AI/人共用的代码地图（类索引 + CLI 命令面 + 领域清单 + 任务库）。
#
# 数据源全部来自代码本身（Javadoc 首句 / @Command 注解 / 枚举与常量 / task.yaml），
# 因此地图不会与实现漂移：改了代码就重跑本脚本，CI 以 --check 模式做漂移门禁。
#
# 用法：
#   bash bin/gen-codemap.sh           # 重新生成 docs/CODEMAP.md
#   bash bin/gen-codemap.sh --check   # 校验现有 CODEMAP 是否与源码一致（不一致退出码 1，CI 用）
#
# 零外部依赖：bash 3.2+（macOS 自带即可）+ POSIX awk + diff。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
OUT="docs/CODEMAP.md"

# ---------- 抽取器：类级 Javadoc 首句 ----------
# 只认列 0 起始的 /**（类级）；跳过 @tag；在第一个「。」或段落结束处截断；
# 去掉 {@code x} / {@link x} 包装与 <strong> 等行内标签。BSD/GNU awk 兼容。
JAVADOC_AWK='
function detag(s,    p, rest, closep, inner, pre) {
    while ((p = index(s, "{@")) > 0) {
        pre = substr(s, 1, p - 1)
        rest = substr(s, p)
        closep = index(rest, "}")
        if (closep == 0) break
        inner = substr(rest, 1, closep - 1)
        sub(/^\{@[a-zA-Z]+ ?/, "", inner)
        s = pre inner substr(rest, closep + 1)
    }
    gsub(/<\/?(strong|em|code|b|i)>/, "", s)
    gsub(/<\/?(ul|ol|li|pre|p|br)>/, " ", s)
    gsub(/  +/, " ", s)
    gsub(/， /, "，", s); gsub(/。 /, "。", s); gsub(/： /, "：", s)
    gsub(/、 /, "、", s); gsub(/； /, "；", s); gsub(/（ /, "（", s); gsub(/ ）/, "）", s)
    gsub(/\|/, "\\&#124;", s)
    return s
}
BEGIN { injd = 0; done = 0; buf = "" }
done { next }
/^\/\*\*/ { injd = 1; next }
injd && /\*\// { done = 1; next }
injd {
    line = $0
    sub(/^ \* ?/, "", line); sub(/^ \*$/, "", line)
    if (line ~ /^@/) { done = 1; next }
    if (line ~ /^<(ul|ol|pre|table)/) { done = 1; next }
    if (line ~ /^<p>/) { if (buf != "") { done = 1; next } else sub(/^<p>/, "", line) }
    if (line == "") { if (buf != "") done = 1; next }
    buf = (buf == "") ? line : buf " " line
    p = index(buf, "。")
    if (p > 0) { buf = substr(buf, 1, p - 1) "。"; done = 1 }
}
END { print detag(buf) }
'

first_sentence() { # <java 文件> —— 输出类级 Javadoc 首句（无则输出告警标记）
    local s
    s="$(awk "$JAVADOC_AWK" "$1")"
    if [ -z "$s" ]; then s="（缺少类级 Javadoc——请补齐，本表以 Javadoc 首句为数据源）"; fi
    printf '%s' "$s"
}

# ---------- 抽取器：@Command 注解（name + description） ----------
# 每遇到 @Command( 开始累积，直到类/字段声明行（以修饰符开头）为止；一个文件可含多个
# （父命令 + 子命令），第一个视为该文件的顶层命令，其余以「父 子」形式展示。
COMMAND_AWK='
function emit(ann,    name, desc) {
    if (match(ann, /name = "[^"]*"/)) {
        name = substr(ann, RSTART + 8, RLENGTH - 9)
    } else return
    desc = ""
    if (match(ann, /description = "[^"]*"/)) {
        desc = substr(ann, RSTART + 15, RLENGTH - 16)
        rest = substr(ann, RSTART + RLENGTH)
        if (rest ~ /^[[:space:]]*\+/) desc = desc "…"
    }
    gsub(/\|/, "\\&#124;", desc)
    print name "\t" desc
}
BEGIN { inann = 0; ann = "" }
/@Command\(/ { inann = 1; ann = "" }
inann && /^[[:space:]]*(public|protected|private|static|final|abstract)[[:space:]]/ {
    emit(ann); inann = 0; ann = ""; next
}
inann { ann = ann $0 " " }
'

generate() { # <输出文件>
    local out="$1"
    {
        echo '# CODEMAP（自动生成，勿手改）'
        echo
        echo '> 本文件由 `bin/gen-codemap.sh` 从源码自动生成：类职责取自类级 Javadoc 首句，'
        echo '> CLI 命令面取自 picocli `@Command` 注解，领域清单取自对应枚举/常量，任务库取自 `tasks/*/task.yaml`。'
        echo '> 更新方式：改代码（或 Javadoc）后执行 `bash bin/gen-codemap.sh`；CI 以 `--check` 校验漂移。'
        echo '> 阅读入口与分区指南见根 `AGENTS.md`；扩展点接线表见 `src/main/java/com/agenteval/AGENTS.md`。'

        # ---------- CLI 命令面 ----------
        echo
        echo '## CLI 命令面'
        echo
        echo '| 命令 | 说明 | 源文件 |'
        echo '| --- | --- | --- |'
        local files f rel top line name desc
        files="$(ls src/main/java/com/agenteval/cli/*.java | LC_ALL=C sort)"
        # Main（根命令）置顶，其余按文件名序。
        for f in src/main/java/com/agenteval/cli/Main.java $files; do
            [ "$f" = "src/main/java/com/agenteval/cli/Main.java" ] && [ -n "${main_done:-}" ] && continue
            [ "$f" = "src/main/java/com/agenteval/cli/Main.java" ] && main_done=1
            rel="${f#src/main/java/}"
            top=""
            while IFS="$(printf '\t')" read -r name desc; do
                [ -z "$name" ] && continue
                if [ -z "$top" ]; then
                    top="$name"
                    echo "| \`$name\` | $desc | \`$rel\` |"
                else
                    echo "| \`$top $name\` | $desc | \`$rel\` |"
                fi
            done <<EOF_CMDS
$(awk "$COMMAND_AWK" "$f")
EOF_CMDS
        done

        # ---------- 生产代码类索引 ----------
        echo
        echo '## 生产代码类索引（src/main/java）'
        local prev_pkg pkg base n_main=0
        prev_pkg=""
        for f in $(find src/main/java -name '*.java' | LC_ALL=C sort); do
            pkg="$(dirname "${f#src/main/java/}")"; pkg="${pkg//\//.}"
            base="$(basename "$f" .java)"
            if [ "$pkg" != "$prev_pkg" ]; then
                echo
                echo "### $pkg"
                echo
                echo '| 类 | 职责（Javadoc 首句） |'
                echo '| --- | --- |'
                prev_pkg="$pkg"
            fi
            echo "| \`$base\` | $(first_sentence "$f") |"
            n_main=$((n_main + 1))
        done

        # ---------- 测试类索引 ----------
        echo
        echo '## 测试类索引（src/test/java）'
        prev_pkg=""
        local n_test=0
        for f in $(find src/test/java -name '*.java' | LC_ALL=C sort); do
            pkg="$(dirname "${f#src/test/java/}")"; pkg="${pkg//\//.}"
            base="$(basename "$f" .java)"
            if [ "$pkg" != "$prev_pkg" ]; then
                echo
                echo "### $pkg"
                echo
                echo '| 测试类 | 覆盖点（Javadoc 首句） |'
                echo '| --- | --- |'
                prev_pkg="$pkg"
            fi
            echo "| \`$base\` | $(first_sentence "$f") |"
            n_test=$((n_test + 1))
        done

        # ---------- check 类型 ----------
        echo
        echo '## judge 规则引擎 check 类型'
        echo
        echo '来源：`judge/RulesFile.SUPPORTED_TYPES`（分派实现在 `judge/RulesJudge`；各类型语义详见 README「写一个新任务」）。'
        echo
        awk '
            /SUPPORTED_TYPES = Set\.of\(/ { grab = 1 }
            grab {
                line = $0
                while (match(line, /"[^"]+"/)) {
                    print "- `" substr(line, RSTART + 1, RLENGTH - 2) "`"
                    line = substr(line, RSTART + RLENGTH)
                }
                if ($0 ~ /\);/) grab = 0
            }
        ' src/main/java/com/agenteval/judge/RulesFile.java

        # ---------- trace 事件 ----------
        echo
        echo '## trace 事件类型'
        echo
        echo '来源：`trace/TraceEventType`（trace.jsonl 中以小写书写）。'
        echo
        echo '| 事件 | 语义 |'
        echo '| --- | --- |'
        awk '
            /^[[:space:]]*\/\*\*.*\*\/[[:space:]]*$/ {
                c = $0
                sub(/^[[:space:]]*\/\*\*[[:space:]]*/, "", c)
                sub(/[[:space:]]*\*\/[[:space:]]*$/, "", c)
                gsub(/\{@code /, "", c); gsub(/\}/, "", c)
                comment = c
                next
            }
            /^[[:space:]]+[A-Z_]+[,;][[:space:]]*$/ {
                name = $0
                gsub(/[[:space:],;]/, "", name)
                printf "| `%s` | %s |\n", tolower(name), comment
                comment = ""
            }
        ' src/main/java/com/agenteval/trace/TraceEventType.java

        # ---------- 任务库 ----------
        echo
        echo '## 任务库（tasks/）'
        echo
        echo '| 任务 | 类型 | tier | 名称 |'
        echo '| --- | --- | --- | --- |'
        local y
        for y in $(ls tasks/*/task.yaml | LC_ALL=C sort); do
            awk '
                /^task_id:/   { id = $2 }
                /^task_type:/ { type = $2 }
                /^tier:/      { tier = $2 }
                /^task_name:/ { line = $0; sub(/^task_name:[[:space:]]*/, "", line); name = line }
                END { printf "| `%s` | %s | %s | %s |\n", id, type, tier, name }
            ' "$y"
        done

        echo
        echo "---"
        echo
        echo "统计：生产类 $n_main 个 · 测试类 $n_test 个。缺 Javadoc 的类会在上表显式标记（本地图以 Javadoc 首句为数据源，请随手补齐）。"
    } > "$out"
}

if [ "${1:-}" = "--check" ]; then
    tmp="$(mktemp)"
    trap 'rm -f "$tmp"' EXIT
    generate "$tmp"
    if ! diff -u "$OUT" "$tmp" >/dev/null 2>&1; then
        echo "CODEMAP 漂移：docs/CODEMAP.md 与源码不一致。请执行 'bash bin/gen-codemap.sh' 重新生成并提交。" >&2
        diff -u "$OUT" "$tmp" >&2 || true
        exit 1
    fi
    echo "CODEMAP 一致 ✓"
else
    generate "$OUT"
    echo "已生成 $OUT"
fi
