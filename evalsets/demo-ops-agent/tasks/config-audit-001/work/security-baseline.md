# 生产环境安全基线（v3）

| 编号 | 规则 | 严重级别 |
| --- | --- | --- |
| SEC-1 | 服务进程必须以非 root 用户运行 | high |
| SEC-2 | 生产 profile 禁止开启 debug 模式 | medium |
| SEC-3 | 日志不得输出完整卡号（必须脱敏） | high |
| SEC-4 | 数据库等凭证不得以明文写入配置文件 | critical |
| SEC-5 | 对公网暴露的端口必须启用 TLS | high |
| SEC-6 | 容器必须声明内存上限 | medium |

严重级别从高到低：critical > high > medium。
