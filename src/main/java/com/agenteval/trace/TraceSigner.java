package com.agenteval.trace;

import com.agenteval.util.Jsons;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * trace 事件的 HMAC-SHA256 签名器：为「事件真伪可核验」提供密码学物证。
 *
 * <p>威胁模型：本地模式下 Agent 与框架共享文件系统，可直接向 append-only 的
 * {@code trace.jsonl} 追加伪造的 {@code tool_call} 成功事件，从而骗过
 * {@code tool_call_required} 类检查（红队 P0-2）。本签名器让每条框架产出的事件都携带
 * 一个用「每 run 私密密钥」计算的签名 {@value #SIG_FIELD}；密钥仅存在于框架进程内存，
 * Agent 执行期间不落盘，因此 Agent 无法为自己伪造的事件补出合法签名。判分侧只统计
 * 签名可核验的事件，伪造事件被静默丢弃。
 *
 * <p>规范化口径：对「去掉签名字段后的事件对象」做紧凑 JSON 序列化并按 UTF-8 取字节。
 * 由于事件对象在写入前后字段顺序保持不变（Jackson {@code ObjectNode} 保序），
 * 「写入→读回→去签名→再序列化」可稳定复现同一字节串，签名因此可离线复核。
 *
 * @author shiyongyin
 * @since 0.2.0
 */
public final class TraceSigner {

    /** 事件对象内承载签名的字段名（不参与签名计算本身）。 */
    public static final String SIG_FIELD = "_sig";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private TraceSigner() {
    }

    /**
     * 为一个「尚未包含签名字段」的事件对象计算签名。
     *
     * @param secret 每 run 私密密钥
     * @param eventWithoutSig 事件对象（调用方保证其中不含 {@value #SIG_FIELD}）
     * @return 十六进制小写签名
     */
    public static String sign(byte[] secret, JsonNode eventWithoutSig) {
        return hmacHex(secret, canonicalBytes(eventWithoutSig));
    }

    /**
     * 核验一个事件对象的签名是否由持有密钥的框架产出。
     *
     * @param secret 每 run 私密密钥（{@code null} 视为无法核验，恒返回 {@code false}）
     * @param event 待核验事件（含或不含签名字段）
     * @return 签名存在且与内容一致时返回 {@code true}
     */
    public static boolean verify(byte[] secret, JsonNode event) {
        if (secret == null || event == null || !event.isObject()) {
            return false;
        }
        JsonNode sig = event.get(SIG_FIELD);
        if (sig == null || !sig.isTextual()) {
            return false;
        }
        ObjectNode copy = ((ObjectNode) event).deepCopy();
        copy.remove(SIG_FIELD);
        String expected = hmacHex(secret, canonicalBytes(copy));
        return constantTimeEquals(expected, sig.asText());
    }

    private static byte[] canonicalBytes(JsonNode node) {
        try {
            return Jsons.jsonCompact().writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("trace 事件无法序列化用于签名", e);
        }
    }

    private static String hmacHex(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(message);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
