# 服务端与 Agent 插件集成指南

手表协议只覆盖“Gateway ↔ Watch”这一段。Agent 插件如何连接 Gateway 由你决定。推荐把 Gateway 做成独立进程，插件通过内部 HTTP 或消息队列发起请求、等待审批。

本仓库的实际情况：`server/` 是可直接运行的 FastAPI Gateway，插件通过 REST API 接入；具体接口、Token 签发、Docker 部署和可复制客户端见 [server/README.md](../server/README.md)。本文的 Gateway 状态机与插件映射仍然适用。

## 1. 推荐架构

```
┌───────────────┐     内部 API（自定义）      ┌──────────────────┐     WebSocket v1     ┌────────┐
│ Agent Plugin  │ ─────────────────────────▶ │ Agent Gateway    │ ◀──────────────────▶ │ Watch  │
│ 工具调用/任务  │ ◀───────────────────────── │ pending + 审计    │                      │ 审批 UI │
└───────────────┘        决定/超时            └──────────────────┘                      └────────┘
```

- Gateway 持有 WebSocket 连接、pending 请求、序号和审计记录。
- Agent 插件在需要人工决策时调用 Gateway，然后 await 决定。
- Watch 只发 `request.respond`。Gateway 把结果返回给插件，并广播 `request.resolved`。
- 插件超时或 Gateway 不可用时必须按拒绝处理。

## 2. Gateway 最小状态机

每个请求：

```
created ──push──▶ pending ──respond──▶ resolved(answered) ──▶ 唤醒插件
                   │
                   ├──expire──▶ resolved(expired) ──▶ 插件按 deny 处理
                   └──cancel──▶ resolved(cancelled) ──▶ 插件中止
```

建议持久化的数据：

| 表/集合 | 关键字段 |
| --- | --- |
| `requests` | `requestId`、`kind`、`payload`、`status`、`createdAt`、`expiresAt`、`resolvedAt`、`optionId` |
| `agents` | `agentId`、`name`、`status`、`task`、`updatedAt` |
| `tasks` | `taskId`、`agentId`、`title`、`summary`、`outcome`、`completedAt`、`durationMs` |
| `decisions` | `requestId`、`deviceId`、`optionId`、`decisionSource`、`timestamp` |
| `devices` | `deviceId`、`tokenHash`、`name`、`lastSeenAt`、`revokedAt` |

建议行为：

1. 插件提交请求时，Gateway 生成 `requestId` 和 `expiresAt`，落库，广播事件，返回一个等待句柄。
2. Watch 决定到达后，Gateway 在事务中校验并写入决定；第一次决定生效，后续决定幂等或拒绝。
3. 广播 `request.resolved`，唤醒插件。
4. 请求到期由服务端定时器处理；到期后落库为 `expired`，插件按拒绝继续。
5. Gateway 重启后从数据库恢复 pending，等 Watch 重连快照即可继续。插件等待句柄建议使用消息队列或可恢复的异步任务，避免进程内 Future 丢结果。

## 3. 内部插件 API 示例

具体接口可以自定义。最小契约只有两点：提交请求、等待决定。

```http
POST /internal/requests
Authorization: Bearer <internal-service-token>
Content-Type: application/json

{
  "kind": "permission",
  "agentId": "claude-code",
  "agentName": "Claude Code",
  "title": "运行构建清理命令？",
  "detail": "rm -rf build && ./gradlew assembleDebug",
  "risk": "high",
  "ttlSec": 120,
  "options": [
    { "id": "allow_once", "label": "允许一次", "style": "approve" },
    { "id": "deny", "label": "拒绝", "style": "deny" }
  ]
}
```

响应：

```json
{
  "requestId": "req_001",
  "status": "pending"
}
```

插件随后通过消息队列、Webhook 或长轮询等待结果。Gateway 返回：

```json
{
  "requestId": "req_001",
  "status": "answered",
  "optionId": "allow_once",
  "deviceId": "f0d1c2b3-...",
  "resolvedAt": 1760000005000
}
```

超时返回：

```json
{
  "requestId": "req_001",
  "status": "expired"
}
```

## 4. 常见 Agent 事件映射

| Agent 行为 | 手表事件 | 说明 |
| --- | --- | --- |
| 工具调用需要授权（shell、写文件、网络、支付） | `permission.request` | `risk` 按操作破坏性设置；命令放 `detail` |
| 需要用户在多个方案中选择 | `question.request` | 必须给 2 到 4 个 `options` |
| 开始/结束工作 | `agent.status` | `working` / `idle` |
| 等待人工决策 | `agent.status` | `waiting`，可同时有 pending |
| 任务完成/失败 | `task.completed` | 附 `summary`，不要塞完整日志 |
| 权限被撤销、任务取消 | `request.resolved` | `resolution: "cancelled"` |

映射建议：

- 高风险判断：删除/覆盖数据、执行未知命令、访问凭据、发布、支付、远程写操作。
- `detail` 只放人类需要判断的关键内容，不要把完整输出或秘密 Token 推到手表。
- `title` 明确“要做什么”，而不是“Agent 请求权限”。
- 选项文案短，例如“允许一次 / 本会话允许 / 拒绝”。不要在手表上放长解释。
- 为高风险请求提供单独的 `deny` 选项；默认行为应是拒绝。

## 5. Python 伪代码

```python
async def ask_watch(gateway, agent, title, detail, risk, options, ttl_sec=120):
    request = await gateway.create_request(
        kind="permission",
        agent_id=agent.id,
        agent_name=agent.name,
        title=title,
        detail=detail,
        risk=risk,
        options=options,
        ttl_sec=ttl_sec,
    )
    result = await gateway.wait_for_result(request["requestId"], timeout=ttl_sec)
    if result["status"] != "answered":
        return "deny"  # 超时/取消 -> 默认拒绝
    return result["optionId"]
```

Gateway 侧广播部分：

```python
async def create_and_push(req):
    req["requestId"] = new_id()
    req["createdAt"] = now_ms()
    req["expiresAt"] = req["createdAt"] + req["ttlSec"] * 1000
    await db.requests.insert(req, status="pending")
    await broadcast("permission.request", req)
    return {"requestId": req["requestId"], "status": "pending"}

async def on_watch_response(device_id, request_id, option_id):
    async with db.transaction():
        req = await db.requests.get_for_update(request_id)
        if req is None:
            return error("request_not_found", request_id=request_id)
        if req.status != "pending":
            return idempotent_or_error(req, option_id)
        if now_ms() >= req.expires_at:
            await db.requests.mark_expired(request_id)
            await broadcast("request.resolved", expired_payload(req))
            return
        await db.requests.resolve(request_id, option_id, device_id)
    await broadcast("request.resolved", answered_payload(request_id, option_id))
    await notify_plugin(request_id, option_id)
```

## 6. 测试流程

1. 启动 mock gateway：
   ```bash
   cd tools/mock-server
   TOKEN=dev-token node server.js
   ```
2. 手表配置地址 `ws://<电脑局域网 IP>:8787/agent` 和 Token `dev-token`，保存并连接。
3. 在运行 mock gateway 的终端输入：
   - `p`：高风险权限请求（测试长按审批）
   - `l`：普通权限请求（测试通知动作）
   - `q`：问题选项
   - `t` / `f`：任务完成/失败
   - `s`：Agent 状态切换
   - `e`：让最早请求过期
4. 观察手表状态、通知、审批结果与 gateway 日志。
5. 断网重连/杀掉应用再打开后，pending 与最近完成应从 snapshot 恢复。

也可以不使用手表，直接运行协议探测客户端：

```bash
node tools/mock-server/test-client.js
```

它会验证握手、快照、权限请求、决定回执。

## 7. 上线前检查

- [ ] 使用 `wss://`，证书有效，禁止明文外网
- [ ] 每台设备独立 Token，支持吊销和轮换
- [ ] 决定落库有审计：谁、哪台设备、哪个选项、何时
- [ ] 超时与断连一律 deny-by-default
- [ ] 重复 `request.respond` 幂等
- [ ] 同一请求不会因为多块手表而执行两次
- [ ] 请求/选项/文本长度受限
- [ ] 日志脱敏，不记录完整 Token、密钥和敏感命令
- [ ] 服务端重启后 pending 可恢复
- [ ] 推送一个高风险请求，确认手表只能长按允许
- [ ] 推送一个请求后断开网络，确认重连后仍可处理且不会重复执行
