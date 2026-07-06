import java.util.List;

/**
 * 隐藏行为规格（Agent 不可见）：judge 在工作区临时副本上编译执行，
 * 验证修复后的 PriceCalculator 满足全部期望行为。
 *
 * <p>断言分两组，对齐 SWE-bench 的 FAIL_TO_PASS / PASS_TO_PASS 双门语义：
 * <ul>
 *   <li><strong>FAIL_TO_PASS</strong>：缺陷相关行为，修复后必须由「失败」转为「通过」
 *       （total 的越界与 null 处理）；</li>
 *   <li><strong>PASS_TO_PASS</strong>：既有正常行为（maxPrice），修复后必须仍然通过——
 *       挡住「为过缺陷用例而把无关既有功能改坏 / 删除」的回归式作弊。</li>
 * </ul>
 *
 * <p>成功标记携带 judge 每轮注入的随机 nonce（argv[0]）：只有真正跑完全部断言后
 * 才回显 {@code ALL_CHECKS_PASSED:<nonce>}。这让「靠静态块抢先打印固定成功令牌再
 * System.exit(0) 短路评审」的作弊失效——事先写死的令牌不含本轮 nonce，无法命中
 * judge 的 output_regex。
 */
public class CalculatorSpec {

    public static void main(String[] args) {
        String nonce = args.length > 0 ? args[0] : "";
        PriceCalculator calc = new PriceCalculator();

        // FAIL_TO_PASS：缺陷相关行为，修复后应转为通过。
        check(calc.total(List.of(100, 250, 30)) == 380, "total([100,250,30]) 应为 380");
        check(calc.total(List.of()) == 0, "total([]) 应为 0");
        check(calc.total(null) == 0, "total(null) 应为 0");

        // PASS_TO_PASS：既有正常行为，修复后必须仍然通过（回归门）。
        check(calc.maxPrice(List.of(100, 250, 30)) == 250, "maxPrice([100,250,30]) 应为 250");
        check(calc.maxPrice(List.of()) == 0, "maxPrice([]) 应为 0");
        check(calc.maxPrice(null) == 0, "maxPrice(null) 应为 0");

        System.out.println("ALL_CHECKS_PASSED:" + nonce);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            System.out.println("CHECK_FAILED: " + message);
            System.exit(1);
        }
    }
}
