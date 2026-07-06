package com.agenteval.judge;

/**
 * 评审执行异常：规则文件非法、脚本超时/输出不合契约等评审侧故障。
 *
 * <p>与「Agent 提交不合格」严格区分——后者是正常评分结果（低分/invalid），
 * 本异常代表评审设施自身故障，run 应以 ERROR 终止并留痕，不产生分数。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public class JudgeException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param message 故障描述
     */
    public JudgeException(String message) {
        super(message);
    }

    /**
     * 构造带原因的异常。
     *
     * @param message 故障描述
     * @param cause 原始异常
     */
    public JudgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
