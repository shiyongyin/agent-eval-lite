package com.agenteval.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 目录树复制 / 删除工具。
 *
 * <p>框架有两处核心用法：① run 启动时把任务的 {@code work/} 复制为 agent 的独立
 * workspace（agent 只在副本上工作，原始任务材料不可被污染）；② judge 执行命令类校验时
 * 构造「临时评审工作区」——workspace 副本之上覆盖 hidden 评审资产，用后即焚，
 * 这是 EdgeBench ephemeral judge container 思想的本地目录版。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class Dirs {

    private Dirs() {
    }

    /**
     * 递归复制目录树（覆盖已存在的同名文件）。
     *
     * @param source 源目录（必须存在）
     * @param target 目标目录（不存在则创建）
     */
    public static void copyTree(Path source, Path target) {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("源目录不存在: " + source);
        }
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, target.resolve(source.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("复制目录失败: " + source + " -> " + target, e);
        }
    }

    /**
     * 递归删除目录树（目录不存在时静默返回）。
     *
     * @param dir 待删除目录
     */
    public static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("删除目录失败: " + dir, e);
        }
    }
}
