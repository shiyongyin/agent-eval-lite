package com.agenteval.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * {@link HtmlRenderer} 的注入面：来自报告 JSON 的任意字符串（feedback 文案、规则 id、Agent 标签）
 * 不得闭合内联数据块或注入标签；内联数据必须能原样取回。
 */
class HtmlRendererTest {

    @Test
    void 数据里的script闭合标签被中和_内联数据仍可原样取回() {
        ObjectNode data = Jsons.json().createObjectNode();
        data.put("feedback", "恶意文案 </script><script>alert(1)</script> 结束");
        data.put("agent", "<img src=\"https://evil.example/x.png\">");

        String html = HtmlRenderer.render(HtmlRenderer.Kind.RUN, "标题 <b>x</b>", data);

        // 数据块内不能出现裸的 </script>，否则浏览器会在这里截断数据块。
        int dataStart = html.indexOf("id=\"ael-data\">") + "id=\"ael-data\">".length();
        int dataEnd = html.indexOf("</script>", dataStart);
        String inlined = html.substring(dataStart, dataEnd);
        assertThat(inlined).doesNotContain("</script>").doesNotContain("</");
        assertThat(html).contains("<title>标题 &lt;b&gt;x&lt;/b&gt;</title>");
        // 页面里除内联数据块外没有任何 http(s) 资源引用。
        assertThat(html.replace(inlined, "")).doesNotContainPattern("(?i)(src|href)=\"https?://");

        JsonNode back = HtmlRenderer.extractInlinedData(html);
        assertThat(back).isEqualTo(data);
    }

    @Test
    void 三种页面类型渲染出对应data_kind() {
        ObjectNode data = Jsons.json().createObjectNode();
        for (HtmlRenderer.Kind kind : HtmlRenderer.Kind.values()) {
            String html = HtmlRenderer.render(kind, "t", data);
            assertThat(html).contains("data-kind=\"" + kind.name().toLowerCase() + "\"");
        }
    }
}
