import java.util.List;

/**
 * 价格合计计算器（修复后的版本，供回放演示使用）。
 */
public class PriceCalculator {

    /**
     * 计算价格列表的合计金额（分）。
     *
     * @param prices 价格列表（分），允许为 null
     * @return 合计金额（分）；null 或空列表返回 0
     */
    public int total(List<Integer> prices) {
        if (prices == null) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < prices.size(); i++) {
            sum += prices.get(i);
        }
        return sum;
    }

    /**
     * 返回价格列表中的最高价（分）——既有功能，行为保持不变。
     *
     * @param prices 价格列表（分），允许为 null
     * @return 最高价（分）；null 或空列表返回 0
     */
    public int maxPrice(List<Integer> prices) {
        if (prices == null || prices.isEmpty()) {
            return 0;
        }
        int max = prices.get(0);
        for (int price : prices) {
            if (price > max) {
                max = price;
            }
        }
        return max;
    }
}
