package com.agenteval.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.MapperBuilder;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON / YAML 序列化的单一出入口。
 *
 * <p>框架对外的所有工件（task.yaml、submission、judge 结果、trace、report）统一使用
 * {@code snake_case} 字段命名，Java 侧保持 camelCase——两个 mapper 在此处集中配置命名策略，
 * 避免各处散落 {@code @JsonProperty} 注解导致口径漂移。
 *
 * <p>反序列化容忍未知字段（向前兼容：老版本引擎读新版工件不崩）、枚举大小写不敏感
 * （YAML 里写 {@code failed_rules}，Java 侧是 {@code FAILED_RULES}）；语义完整性由各自的
 * 加载器/校验器负责把关。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class Jsons {

    private static final ObjectMapper JSON = configure(JsonMapper.builder());
    private static final ObjectMapper YAML = configure(YAMLMapper.builder());
    private static final ObjectWriter JSON_COMPACT =
            JSON.writer().without(SerializationFeature.INDENT_OUTPUT);

    private Jsons() {
    }

    /**
     * 返回 JSON mapper（snake_case、ISO-8601 时间、缩进输出、容忍未知字段）。
     *
     * @return 进程级共享的 JSON mapper
     */
    public static ObjectMapper json() {
        return JSON;
    }

    /**
     * 返回单行紧凑 JSON writer——专供 JSONL（trace）写入，禁止缩进。
     *
     * @return 紧凑输出的 writer
     */
    public static ObjectWriter jsonCompact() {
        return JSON_COMPACT;
    }

    /**
     * 返回 YAML mapper（配置口径与 JSON mapper 一致）。
     *
     * @return 进程级共享的 YAML mapper
     */
    public static ObjectMapper yaml() {
        return YAML;
    }

    private static ObjectMapper configure(MapperBuilder<?, ?> builder) {
        return builder
                .addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }
}
