package com.agenteval.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * SHA-256 指纹工具：为「评分可复现」提供物证。
 *
 * <p>Judge 结果中记录 hidden 目录指纹与 submission 文件指纹——事后任何人都能验证
 * 「当时用的是哪一版规则、评的是哪一份提交」，这是 EdgeBench 可复现评分思想的最小落地。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class Hashes {

    private Hashes() {
    }

    /**
     * 计算单个文件内容的 SHA-256。
     *
     * @param file 目标文件
     * @return 十六进制小写摘要
     */
    public static String sha256OfFile(Path file) {
        try {
            return hex(digest().digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + file, e);
        }
    }

    /**
     * 计算整个目录树的稳定指纹：按相对路径排序，逐一混入「路径 + 内容」。
     *
     * <p>任何文件的增删改都会改变指纹；空目录与文件系统元数据（mtime 等）不参与计算，
     * 保证跨机器可复现。
     *
     * @param dir 目标目录
     * @return 十六进制小写摘要；目录不存在时返回 {@code "absent"}
     */
    public static String sha256OfDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return "absent";
        }
        MessageDigest digest = digest();
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                    .toList();
            for (Path file : files) {
                digest.update(dir.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("遍历目录失败: " + dir, e);
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
