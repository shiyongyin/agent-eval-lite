# Release Notes v2.0

## 特性

- 新增用户标签能力：`GET /v1/users/{id}/tags`。
- 列表接口支持分页参数 `page` / `page_size`。
- 管理后台新增操作审计页。

## 变更

- **用户对象移除 `nickname` 字段**：`GET /v1/users` 与 `POST /v1/users` 不再返回/接受
  `nickname`，改由 `display_name` 承担展示名职责。仍向 `POST` 传该字段会返回 `400`。
- **`session.timeout` 单位由秒改为毫秒**：原配置 `1800` 现在表示 1.8 秒，
  升级时必须把既有配置值乘以 1000。
- 日志格式从纯文本切换为 JSON（旧格式可通过 `logging.legacy=true` 临时保留，v3 移除该开关）。

## 修复

- 修复分页越界时返回 500 的问题。
