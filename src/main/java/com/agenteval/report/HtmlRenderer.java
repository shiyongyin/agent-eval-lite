package com.agenteval.report;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把公开报告 JSON（run / suite / history）渲染成单文件、零外链的 HTML 视图。
 *
 * <p>HTML 只是 JSON 的展示层：数据以 {@code <script type="application/json" id="ael-data">} 原样内联，
 * 页面脚本只负责渲染，不新增任何字段；不读取 {@code judge/}、{@code hidden/}、{@code traces/}，
 * 因此 {@code private_notes} 之类的私有诊断天然不会进入页面。所有来自 JSON 的字符串在浏览器侧经
 * {@code textContent} 写入，在内联阶段又把 {@code </} 转义为 {@code <\/}，防止 feedback 文案里的
 * HTML 片段闭合脚本块。
 *
 * @author shiyongyin
 * @since 0.5.0
 */
public final class HtmlRenderer {

    /** 页面类型：决定浏览器侧走哪套渲染逻辑。 */
    public enum Kind {
        /** 单次 run 的 report.json。 */
        RUN,
        /** suite_report.json（single / comparison 两种模式由 JSON 内 {@code mode} 区分）。 */
        SUITE,
        /** history.json。 */
        HISTORY;

        String id() {
            return name().toLowerCase();
        }
    }

    private static final String TEMPLATE_RESOURCE = "report/page.html";
    private static final Pattern DATA_BLOCK = Pattern.compile(
            "<script type=\"application/json\" id=\"ael-data\">(.*?)</script>", Pattern.DOTALL);

    private HtmlRenderer() {
    }

    /**
     * 渲染一页自包含 HTML。
     *
     * @param kind 页面类型
     * @param title 页面标题（将被 HTML 转义）
     * @param data 与同目录 {@code .json} 文件完全相同的报告数据
     * @return HTML 文本
     */
    public static String render(Kind kind, String title, JsonNode data) {
        String json = data.toString().replace("</", "<\\/");
        return template()
                .replace("__TITLE__", escapeHtml(title))
                .replace("__KIND__", kind.id())
                .replace("__DATA__", json);
    }

    /**
     * 从渲染结果中取回内联的数据块（测试与离线核对用：HTML 内联数据必须与 {@code .json} 文件一致）。
     *
     * @param html {@link #render} 的输出
     * @return 内联 JSON
     * @throws IllegalArgumentException 页面里没有数据块或数据块不是合法 JSON
     */
    public static JsonNode extractInlinedData(String html) {
        Matcher m = DATA_BLOCK.matcher(html);
        if (!m.find()) {
            throw new IllegalArgumentException("HTML 中没有 ael-data 数据块");
        }
        try {
            return Jsons.json().readTree(m.group(1).replace("<\\/", "</"));
        } catch (IOException e) {
            throw new IllegalArgumentException("ael-data 数据块不是合法 JSON", e);
        }
    }

    static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String template() {
        try (InputStream in = HtmlRenderer.class.getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少 HTML 报告模板资源: " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 HTML 报告模板失败: " + TEMPLATE_RESOURCE, e);
        }
    }
}
