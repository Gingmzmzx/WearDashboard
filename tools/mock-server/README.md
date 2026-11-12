# Mock Agent Gateway

无第三方依赖的本地开发服务端，实现 [协议 v1](../../docs/PROTOCOL.md) 的握手、快照、状态广播、权限/提问、决定回执和过期。

## 运行

```bash
TOKEN=dev-token PORT=8787 node server.js
```

输出：

- WebSocket 地址：`ws://<lan-ip>:8787/agent`
- 认证：`Authorization: Bearer dev-token`
- 监听默认 `0.0.0.0`，同一局域网的手表可直连

手表设置页填入地址和 Token 后保存。

## 终端命令

| 命令 | 效果 |
| --- | --- |
| `p` | 高风险权限请求（测试长按允许） |
| `l` | 低风险权限请求（测试通知内直接审批） |
| `q` | 三选项问题 |
| `t` | 成功任务 |
| `f` | 失败任务 |
| `s` | 切换 Agent 状态 |
| `e` | 使最早的 pending 请求过期 |
| `h` | 帮助 |

同一请求的决定会广播给所有已连接手表。

## HTTP 控制

```bash
curl -X POST http://127.0.0.1:8787/demo/permission
curl -X POST http://127.0.0.1:8787/demo/permission-low
curl -X POST http://127.0.0.1:8787/demo/question
curl -X POST http://127.0.0.1:8787/demo/task
curl -X POST http://127.0.0.1:8787/demo/task-failed
curl -X POST http://127.0.0.1:8787/demo/status
curl -X POST http://127.0.0.1:8787/demo/expire
curl http://127.0.0.1:8787/health
```

## 协议探测

```bash
node test-client.js
```

脚本使用 Node 内置 WebSocket 连接、握手、触发高风险权限、提交 `allow_once`，验证收到 `request.resolved`。Node 22+。

## 说明

这是开发工具，不是生产 Gateway：

- 状态只保存在内存，进程重启即丢失
- Token 固定，无设备级签发/吊销
- 允许 `?token=` 查询参数，仅为了 Node 内置 WebSocket 便于本地测试；正式实现必须使用 `Authorization` 头
- 无 TLS、无限流、无审计、无持久化

生产实现请参考 [集成指南](../../docs/INTEGRATION.md)。
