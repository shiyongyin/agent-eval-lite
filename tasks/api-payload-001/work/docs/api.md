# 创建订单接口（POST /v1/orders）

## 请求体字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `order_type` | string | 是 | `STANDARD`（普通）或 `EXPRESS`（加急） |
| `customer` | object | 是 | `{customer_id: string, level: "REGULAR" | "VIP"}` |
| `items` | array | 是 | 每项 `{sku: string, qty: int ≥1, unit_price_cents: int ≥0}` |
| `total_amount_cents` | int | 是 | 订单总额（分），必须按下方规则计算 |
| `currency` | string | 是 | 固定 `"CNY"` |

## 金额规则

1. 商品小计 = Σ(`qty` × `unit_price_cents`)；
2. **VIP 客户享受 9 折**：总额 = 商品小计 × 0.9，**向下取整到分**；
3. 普通客户总额 = 商品小计；
4. 加急不影响金额，只影响 `order_type`。

## 常见错误

- 忘记打折或把折扣算成 10 元减免；
- `total_amount_cents` 用元为单位（必须是分）；
- `currency` 写成小写 `cny`。
