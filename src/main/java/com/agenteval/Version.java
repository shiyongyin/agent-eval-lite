package com.agenteval;

/**
 * 框架版本常量：写入 judge 结果的 {@code reproducibility.engine_version}，
 * 保证任何历史评分都能追溯到当时的引擎版本。
 *
 * @author shiyongyin
 * @since 0.1.0
 */
public final class Version {

    /** 引擎版本标识（随发布手工递增，与 pom 版本保持一致）。 */
    public static final String ENGINE = "agent-eval-lite/0.1.0";

    private Version() {
    }
}
