package com.agenteval.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * 极简 JSON 路径解析器：支持 {@code $}、{@code $.a.b}、{@code $.items[0].sku} 三种形态。
 *
 * <p>刻意不引入完整 JSONPath 依赖：判分规则里只需要「定位一个确定节点」，
 * 通配符/过滤器等能力当前用不上（用上了往往意味着规则写得不够确定）。不支持的语法
 * 在规则加载期即抛错，避免评分期才发现。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class JsonPaths {

    private JsonPaths() {
    }

    /**
     * 按路径解析节点。
     *
     * @param root 根节点
     * @param path 形如 {@code $.final_payload.total_amount_cents} 或 {@code $.tests_run[0].result}
     * @return 命中的节点；路径不存在时返回 {@link MissingNode}
     * @throws IllegalArgumentException 路径语法非法时
     */
    public static JsonNode resolve(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("$")) {
            throw new IllegalArgumentException("path 必须以 $ 开头: " + path);
        }
        JsonNode current = root;
        int i = 1;
        while (i < trimmed.length()) {
            char c = trimmed.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < trimmed.length() && trimmed.charAt(end) != '.' && trimmed.charAt(end) != '[') {
                    end++;
                }
                String field = trimmed.substring(i + 1, end);
                if (field.isBlank()) {
                    throw new IllegalArgumentException("path 存在空字段名: " + path);
                }
                current = current.path(field);
                i = end;
            } else if (c == '[') {
                int close = trimmed.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("path 缺少 ]: " + path);
                }
                String indexText = trimmed.substring(i + 1, close);
                int index;
                try {
                    index = Integer.parseInt(indexText.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("下标必须是整数: " + path);
                }
                current = current.path(index);
                i = close + 1;
            } else {
                throw new IllegalArgumentException("非法 path 字符 '" + c + "': " + path);
            }
            if (current.isMissingNode()) {
                return MissingNode.getInstance();
            }
        }
        return current;
    }
}
