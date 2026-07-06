package com.agenteval.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * {@link JsonPaths} 的行为契约测试：支持的三种形态 + 非法语法拒绝。
 */
class JsonPathsTest {

    private JsonNode sample() throws Exception {
        return Jsons.json().readTree("""
                {"a": {"b": [ {"c": 42}, {"c": "x"} ]}, "flag": true}
                """);
    }

    @Test
    void 根路径返回整棵树() throws Exception {
        JsonNode root = sample();
        assertThat(JsonPaths.resolve(root, "$")).isSameAs(root);
    }

    @Test
    void 字段与下标混合定位() throws Exception {
        assertThat(JsonPaths.resolve(sample(), "$.a.b[0].c").asInt()).isEqualTo(42);
        assertThat(JsonPaths.resolve(sample(), "$.a.b[1].c").asText()).isEqualTo("x");
        assertThat(JsonPaths.resolve(sample(), "$.flag").asBoolean()).isTrue();
    }

    @Test
    void 不存在的路径返回MissingNode() throws Exception {
        assertThat(JsonPaths.resolve(sample(), "$.a.nope").isMissingNode()).isTrue();
        assertThat(JsonPaths.resolve(sample(), "$.a.b[9].c").isMissingNode()).isTrue();
    }

    @Test
    void 非法语法直接拒绝() throws Exception {
        JsonNode root = sample();
        assertThatThrownBy(() -> JsonPaths.resolve(root, "a.b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPaths.resolve(root, "$.a.b[x]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPaths.resolve(root, "$.a.b[0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
