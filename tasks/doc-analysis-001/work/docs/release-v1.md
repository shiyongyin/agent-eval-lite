# Release Notes v1.0

基线版本，无升级事项。

## 特性

- 用户服务上线：`GET /v1/users`、`POST /v1/users`，用户对象含 `id`、`name`、`nickname`、`email` 字段。
- 会话管理：配置项 `session.timeout` 控制会话超时，单位**秒**，默认 `1800`。
- 运行环境：Java 11+，默认监听端口 `8080`。
- 管理后台首页支持深色模式。
