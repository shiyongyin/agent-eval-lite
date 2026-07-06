package com.agenteval.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 运行标识生成器：时间戳前缀 + 短随机尾，兼顾可读性、可排序性与唯一性。
 *
 * <p>形如 {@code run_20260706_213000_a3f9}。不用 UUID 是因为评估产物目录要靠人眼浏览、
 * 靠文件名排序对比多次运行，纯 UUID 对人不友好。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class Ids {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private Ids() {
    }

    /**
     * 生成新的 run id。
     *
     * @return 形如 {@code run_20260706_213000_a3f9} 的 id
     */
    public static String newRunId() {
        return "run_" + TS.format(LocalDateTime.now()) + "_" + randomSuffix(4);
    }

    /**
     * 生成 attempt id（三位序号，便于文件名排序）。
     *
     * @param attemptNumber 从 1 开始的提交序号
     * @return 形如 {@code attempt_001} 的 id
     */
    public static String attemptId(int attemptNumber) {
        return String.format("attempt_%03d", attemptNumber);
    }

    private static String randomSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
