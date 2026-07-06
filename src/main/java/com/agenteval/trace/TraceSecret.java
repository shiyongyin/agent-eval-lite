package com.agenteval.trace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每 run 的 trace 签名密钥管理：生成、（在 Agent 停止后）落盘、离线加载。
 *
 * <p>安全不变量：<strong>Agent 执行期间，签名密钥绝不出现在磁盘上</strong>。
 * 因此密钥只在 run「收尾」（Agent 已停止）时才写入 {@code <runDir>/.ael/trace.key}，
 * 供离线复核与 resume 复用；一旦 resume 再次拉起（Agent 尚未启动），
 * {@link #obtain(Path)} 会读出并<strong>立即删除</strong>该文件，使密钥重新回到「仅内存」状态。
 *
 * <p>此文件对同机同用户的攻击者并非不可读——它只保证「Agent 运行的时间窗内密钥不在盘上」。
 * 抵御 run 结束后对产物的离线篡改属于另一层（目录指纹 + 从干净任务目录复算），
 * 彻底隔离需 Phase 4 容器化。
 *
 * @author shiyongyin
 * @since 0.2.0
 */
public final class TraceSecret {

    private static final Logger log = LoggerFactory.getLogger(TraceSecret.class);
    private static final String KEY_RELATIVE_PATH = ".ael/trace.key";
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceSecret() {
    }

    /**
     * 取得本 run 的签名密钥：若存在上一段（收尾时写入的）密钥则读出并删除以复用（resume 场景），
     * 否则新生成一把。返回后密钥即处于「仅内存」状态。
     *
     * @param runDir run 目录
     * @return 32 字节密钥
     */
    public static byte[] obtain(Path runDir) {
        Path keyFile = runDir.resolve(KEY_RELATIVE_PATH);
        if (Files.isRegularFile(keyFile)) {
            try {
                byte[] secret = HexFormat.of().parseHex(
                        Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                Files.delete(keyFile);
                return secret;
            } catch (IOException | IllegalArgumentException e) {
                log.warn("读取既有 trace 密钥失败，改用新密钥: {}", e.getMessage());
            }
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /**
     * 在 run 收尾（Agent 已停止）时落盘密钥，供离线复核与后续 resume 复用。
     *
     * @param runDir run 目录
     * @param secret 本 run 密钥
     */
    public static void save(Path runDir, byte[] secret) {
        Path keyFile = runDir.resolve(KEY_RELATIVE_PATH);
        try {
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, HexFormat.of().formatHex(secret), StandardCharsets.UTF_8);
            restrictPermissions(keyFile);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 trace 密钥失败: " + keyFile, e);
        }
    }

    /**
     * 离线加载既有密钥（不删除）——供 {@code agent-eval judge --trace} 复核签名时使用。
     *
     * @param runDir run 目录
     * @return 密钥；不存在时返回 {@code null}
     */
    public static byte[] load(Path runDir) {
        Path keyFile = runDir.resolve(KEY_RELATIVE_PATH);
        if (!Files.isRegularFile(keyFile)) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(
                    Files.readString(keyFile, StandardCharsets.UTF_8).trim());
        } catch (IOException | IllegalArgumentException e) {
            log.warn("离线加载 trace 密钥失败: {}", e.getMessage());
            return null;
        }
    }

    private static void restrictPermissions(Path keyFile) {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyFile, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            // 非 POSIX 文件系统（如 Windows）忽略——密钥安全性不依赖文件权限，仅作纵深防御。
            log.debug("无法限制 trace 密钥文件权限（非 POSIX 文件系统？）: {}", e.getMessage());
        }
    }
}
