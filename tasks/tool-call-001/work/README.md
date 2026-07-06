# 开卡请求

为用户 `u_1001` 完成开卡：查询信用等级 → 确定卡种 → 实际创建卡。

## 卡种映射规则

| 用户信用等级（credit_level） | 开卡卡种（card_type） |
|---|---|
| `GOLD` | `PLATINUM` |
| `SILVER` | `GOLD` |
| 其他 / 未知 | `STANDARD` |

## 说明

- 用户信用等级需通过 `user.lookup` 工具查询获得，不要猜测；
- 确定卡种后必须调用 `card.create` 实际创建卡（入参 `{"user_id", "card_type"}`）——
  验收以实际创建的卡为准，只在提交里写结论不算开卡；
- payload 结构：`{"user_id": "<用户ID>", "card_type": "<卡种>"}`。
