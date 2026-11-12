# Watch Agent Protocol v1

本文档定义手表端与服务端（Agent Gateway）之间的通信协议。手表只负责展示状态和收集人的决定；服务端负责持有 Agent 会话、等待决定、执行超时策略，并在 Agent 插件与手表之间转发。

- 传输：WebSocket
- 序列化：UTF-8 JSON
- 协议版本：`v: 1`
- 手表最低系统：Android 7.0 (API 24)
- 参考实现：生产型 Gateway 见 [server/](../server/README.md)；无依赖 mock 见 [tools/mock-server/server.js](../tools/mock-server/server.js)

## 1. 角色与连接

```
Agent plugin  ──(内部接口，自定义)──  Gateway / Server  ──WebSocket(JSON v1)──  Watch
```

手表向 Gateway 建立一条长期 WebSocket。Gateway 可以同时保留多个手表连接，同一台手表也可以配置一个 Gateway 地址。

手表配置项：

| 配置 | 说明 |
| --- | --- |
| `server_url` | 完整 WebSocket 地址，如 `wss://agent.example.com/agent` |
| `token` | 设备级 Bearer Token，使用 Android Keystore 加密保存 |
| `device_name` | 展示名，握手时上报 |

建议为每块手表签发独立 Token，服务端可单独吊销。Token 可以审批命令，按高敏凭据对待。

## 2. WebSocket 握手与认证

1. 手表发起标准 WebSocket Upgrade 请求。
2. 请求必须带：
   ```http
   Authorization: Bearer <device-token>
   User-Agent: WatchDashboard/1.0
   ```
3. 认证失败时，服务端应返回 HTTP `401` 或 `403`，或在升级后立即以 WebSocket close code `4001` / `4003` 关闭连接。
4. 手表遇到 `401`、`403`、`4001`、`4003` 时进入“认证失败”状态，停止自动重连，直到用户修改配置或手动重试。
5. 生产环境必须使用 `wss://`。手表为兼容局域网开发也允许 `ws://`，但明文连接会暴露 Token 和审批内容。

服务端不应把 Token 放进 URL。查询参数便于调试但不属于正式协议。

## 3. 消息信封

所有 v1 消息都是一个 JSON 对象：

```json
{
  "v": 1,
  "id": "evt_000123",
  "seq": 123,
  "type": "permission.request",
  "ts": 1760000000000,
  "payload": {}
}
```

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `v` | int | 是 | 协议版本，当前固定为 `1` |
| `id` | string | 是 | 全局唯一事件 ID，建议 `evt_` 前缀。手表用它做日志和排错 |
| `seq` | int64 | 服务端状态事件必需 | 单调递增序号。`snapshot` 用它表示同步游标 |
| `type` | string | 是 | 事件类型 |
| `ts` | int64 | 是 | 服务端产生事件的 Unix 时间戳，毫秒 |
| `payload` | object | 视类型而定 | 事件体 |

时间字段全部使用 Unix epoch 毫秒（UTC），不要使用本地时间字符串。

### 3.1 序号、去重与快照

- 服务端状态变更事件（`agent.status`、`permission.request`、`question.request`、`task.completed`、`request.resolved`）必须带严格递增的 `seq`。
- 每次成功连接，服务端都必须发送 `snapshot`。`snapshot` 是权威状态：手表收到后清空本地缓存并替换。
- 手表会忽略 `seq <= lastSeq` 的状态事件。快照会重置 `lastSeq`（包括服务端计数器归零的情况）。
- 如果手表检测到 `seq` 跳号（丢帧或乱序），会主动断开重连并重新获取 `snapshot`，以服务端状态为准。
- 服务端重启后序号可以重新从 1 开始，因为新的 `snapshot` 会重置客户端游标。
- 手表会发送 `ack` 上报已处理到哪个序号。`ack` 只用于服务端裁剪日志，不能替代快照恢复。
- 未知 `type`、未知字段必须被忽略；不要因为客户端忽略字段而改变必填语义。

## 4. 连接时序

```
Watch                                    Gateway
  │  WebSocket Upgrade + Bearer token      │
  │ ──────────────────────────────────────▶ │
  │                                         │
  │  hello                                  │
  │ ──────────────────────────────────────▶ │
  │                                         │
  │  hello.ok                               │
  │ ◀────────────────────────────────────── │
  │                                         │
  │  snapshot (authoritative)               │
  │ ◀────────────────────────────────────── │
  │                                         │
  │  ack {seq: N}                           │
  │ ──────────────────────────────────────▶ │
  │                                         │
  │  agent.status / permission.request ...  │
  │ ◀────────────────────────────────────── │
  │                                         │
  │  request.respond {requestId, optionId}  │
  │ ──────────────────────────────────────▶ │
  │                                         │
  │  request.resolved (broadcast)           │
  │ ◀────────────────────────────────────── │
```

### 4.1 客户端 hello

连接建立后立即发送：

```json
{
  "v": 1,
  "id": "cli_2c4f...",
  "type": "hello",
  "ts": 1760000000000,
  "payload": {
    "protocol": 1,
    "deviceId": "f0d1c2b3-...",
    "deviceName": "My Watch",
    "appVersion": "1.0",
    "cursor": 122
  }
}
```

`cursor` 是手表最后处理的 `seq`，仅作辅助信息。服务端仍然必须发送完整 `snapshot`。服务端可以拒绝 `protocol != 1` 的连接。

### 4.2 服务端 hello.ok

```json
{
  "v": 1,
  "id": "evt_hello_1",
  "seq": 0,
  "type": "hello.ok",
  "ts": 1760000000000,
  "payload": {
    "protocol": 1,
    "serverName": "agent-gateway",
    "sessionId": "session_abc",
    "heartbeatSec": 25
  }
}
```

`serverName` 会显示在手表设置页。`seq` 可以是 0；快照会给出真正的游标。

### 4.3 服务端 snapshot

`hello.ok` 之后立即发送，每次重连都发送：

```json
{
  "v": 1,
  "id": "snap_000123",
  "seq": 123,
  "type": "snapshot",
  "ts": 1760000000000,
  "payload": {
    "agents": [
      {
        "id": "claude-code",
        "name": "Claude Code",
        "status": "working",
        "detail": "Watching the repository",
        "task": "Refactor auth middleware",
        "updatedAt": 1760000000000
      }
    ],
    "pending": [
      {
        "requestId": "req_001",
        "kind": "permission",
        "agentId": "claude-code",
        "agentName": "Claude Code",
        "title": "运行构建清理命令？",
        "detail": "rm -rf build && ./gradlew assembleDebug",
        "risk": "high",
        "createdAt": 1760000000000,
        "expiresAt": 1760000120000,
        "options": [
          { "id": "allow_once", "label": "允许一次", "style": "approve" },
          { "id": "deny", "label": "拒绝", "style": "deny" }
        ]
      }
    ],
    "recent": [],
    "serverTime": 1760000000000
  }
}
```

快照要求：

- `agents`、`pending`、`recent` 均为数组；空数组也必须出现。
- `pending` 必须包含当前所有未处理请求，已过期的不应出现。
- `recent` 为最近完成的任务，建议最多 10 条，按完成时间倒序。
- `seq` 为当前服务端序号，手表会把它作为新游标。
- 如果手表此前已经提交决定但服务端尚未收到，手表会在快照后重新发送 outbox 中的 `request.respond`。服务端必须幂等处理。

## 5. 服务端事件

### 5.1 agent.status

表示 Agent 当前状态。可重复发送，按 `agent.id` 覆盖。

```json
{
  "v": 1,
  "id": "evt_124",
  "seq": 124,
  "type": "agent.status",
  "ts": 1760000000000,
  "payload": {
    "id": "claude-code",
    "name": "Claude Code",
    "status": "waiting",
    "detail": "Waiting for permission",
    "task": "Refactor auth middleware",
    "updatedAt": 1760000000000
  }
}
```

`status` 枚举：`idle`、`working`、`waiting`、`error`、`offline`。

### 5.2 permission.request

请求用户授权一个操作。`payload` 就是快照 `pending[]` 里的同一个对象。

```json
{
  "v": 1,
  "id": "evt_125",
  "seq": 125,
  "type": "permission.request",
  "ts": 1760000000000,
  "payload": {
    "requestId": "req_001",
    "kind": "permission",
    "agentId": "claude-code",
    "agentName": "Claude Code",
    "title": "运行构建清理命令？",
    "detail": "rm -rf build && ./gradlew assembleDebug",
    "risk": "high",
    "createdAt": 1760000000000,
    "expiresAt": 1760000120000,
    "options": [
      { "id": "allow_once", "label": "允许一次", "style": "approve" },
      { "id": "deny", "label": "拒绝", "style": "deny" }
    ]
  }
}
```

字段：

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `requestId` | string | 是 | 幂等键，建议全局唯一 |
| `kind` | string | 是 | `permission` |
| `agentId` | string | 是 | 发起方 ID |
| `agentName` | string | 建议 | 展示名，缺省用 agentId |
| `title` | string | 是 | 一句话说明（建议不超过 60 字） |
| `detail` | string | 否 | 命令、diff 摘要、参数等。手表按纯文本展示 |
| `risk` | string | 是 | `low`、`medium`、`high` |
| `createdAt` | int64 | 是 | 创建时间 |
| `expiresAt` | int64 | 建议 | 过期时间。到期后服务端必须拒绝决定 |
| `options` | array | 是 | 1 到 4 个选项 |
| `allowFreeText` | bool | 否 | 保留字段；当前手表不支持自由文本输入，必须提供选项 |

`options[]`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 客户端回传的选项 ID，如 `allow_once`、`allow_session`、`deny` |
| `label` | string | 显示文案，建议不超过 12 个汉字 |
| `style` | string | `approve`、`deny`、`neutral`；决定按钮颜色与危险操作交互 |

高风险请求的 `style: approve` 选项在手表详情页必须长按 1 秒确认。系统通知中不会为高风险请求提供“允许”按钮，只能打开手表应用处理。

### 5.3 question.request

请用户做选择。与权限请求不同，`kind` 为 `question`，并包含 `question` 与 `context`。

```json
{
  "v": 1,
  "id": "evt_126",
  "seq": 126,
  "type": "question.request",
  "ts": 1760000000000,
  "payload": {
    "requestId": "req_002",
    "kind": "question",
    "agentId": "claude-code",
    "agentName": "Claude Code",
    "title": "构建缓存冲突，下一步怎么处理？",
    "question": "构建缓存冲突，下一步怎么处理？",
    "context": "两个 Gradle 任务同时写入了 build/cache。",
    "createdAt": 1760000000000,
    "expiresAt": 1760000300000,
    "allowFreeText": false,
    "options": [
      { "id": "retry_clean", "label": "清理后重试", "style": "approve" },
      { "id": "inspect", "label": "先人工检查", "style": "neutral" },
      { "id": "stop", "label": "停止任务", "style": "deny" }
    ]
  }
}
```

问题必须提供选项。手表展示选项并回传 `optionId`；`allowFreeText` 当前仅作协议保留。

### 5.4 request.resolved

请求被某块手表、其他设备、服务端或超时机制处理。所有在线手表都应收到广播。

```json
{
  "v": 1,
  "id": "evt_127",
  "seq": 127,
  "type": "request.resolved",
  "ts": 1760000000000,
  "payload": {
    "requestId": "req_001",
    "optionId": "allow_once",
    "resolution": "answered",
    "by": "watch",
    "resolvedAt": 1760000005000
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `resolution` | `answered`、`expired`、`cancelled` |
| `by` | `watch`、`other`、`server` |
| `optionId` | 仅在 `answered` 时出现 |

手表收到后移除待处理项和 outbox。

### 5.5 task.completed

任务结束通知（成功、失败或取消）：

```json
{
  "v": 1,
  "id": "evt_128",
  "seq": 128,
  "type": "task.completed",
  "ts": 1760000000000,
  "payload": {
    "taskId": "task_42",
    "agentId": "claude-code",
    "agentName": "Claude Code",
    "title": "重构登录中间件",
    "summary": "已更新 4 个文件，全部测试通过",
    "outcome": "success",
    "durationMs": 184000,
    "completedAt": 1760000000000
  }
}
```

`outcome`：`success`、`failed`、`cancelled`。手表只保留最近 10 条；不要依赖它作为完整历史。

### 5.6 error

用于回应无法处理的客户端消息，或服务端显式错误：

```json
{
  "v": 1,
  "id": "err_001",
  "type": "error",
  "ts": 1760000000000,
  "payload": {
    "code": "request_expired",
    "message": "Request expired before the decision arrived",
    "requestId": "req_001"
  }
}
```

`requestId` 可选，但涉及 `request.respond` 的错误必须带上。手表对带 `requestId` 的错误会移除本地 outbox 与待处理项；因此不要用带 `requestId` 的 error 表达可重试的瞬时故障。

错误码建议：

| code | 含义 |
| --- | --- |
| `bad_json` | 帧不是合法 JSON |
| `bad_version` | 不支持的协议版本 |
| `unknown_type` | 不支持的消息类型 |
| `bad_request` | 缺少必需字段 |
| `request_not_found` | 请求不存在或已被清理 |
| `request_expired` | 请求已过期 |
| `already_resolved` | 已按另一个选项处理 |
| `invalid_option` | optionId 不属于该请求 |
| `unauthorized` | 认证失败 |
| `server_error` | 服务端内部错误 |

## 6. 客户端事件

### 6.1 hello

见 4.1。每条连接只发一次。

### 6.2 ack

```json
{
  "v": 1,
  "id": "cli_9f...",
  "type": "ack",
  "ts": 1760000000000,
  "payload": { "seq": 128 }
}
```

手表最多约每 800ms 合并发送一次。服务端可以忽略。

### 6.3 request.respond

```json
{
  "v": 1,
  "id": "cli_7a...",
  "type": "request.respond",
  "ts": 1760000000000,
  "payload": {
    "requestId": "req_001",
    "optionId": "allow_once"
  }
}
```

这是手表唯一的写操作。语义：

- 服务端先校验 Token、请求是否存在、是否过期、`optionId` 是否属于该请求。
- 成功后移除 pending，广播 `request.resolved`，并唤醒等待中的 Agent 插件。
- 同一 `requestId` 的重复响应必须幂等：
  - 相同 `optionId`：可以再次发送 `request.resolved` 或直接忽略。
  - 不同 `optionId`：返回 `already_resolved`，不得改变第一次决定。
- 手表有持久化 outbox：如果发送后连接断开，重连拿到快照时若该请求仍在 pending，会重新发送同一个 `request.respond`。服务端不能把重复包当作新的审批。
- 本地 outbox 保留到该请求的 `expiresAt`；未提供 `expiresAt` 时保留 5 分钟。过期后本地丢弃，服务端仍应自行执行超时策略。

## 7. 超时与审批安全

- 服务端是超时的唯一权威。`expiresAt` 建议 permission 120 秒、question 300 秒。
- 到期后服务端移除 pending 并广播 `request.resolved`，`resolution: "expired"`。
- 等待决定的插件必须把超时视为拒绝（deny-by-default），绝不能自动放行。
- 手表在 `expiresAt` 到达后立即禁用按钮；服务端仍需自行校验，不能相信客户端时钟。
- 手表不会自动审批任何请求。高风险审批需要长按；普通审批需要用户显式点击通知动作或详情页按钮。
- 系统通知在锁屏可能隐藏内容（visibility=private），不要依赖通知正文传递敏感信息。
- 服务端应记录每次决定的操作者、设备、请求内容、时间和结果，便于审计。

## 8. 多设备与状态同步

- 同一账号可以连接多块手表。任一设备提交决定后，服务端广播 `request.resolved`，其他设备移除该请求。
- 新连接的手表总是通过 `snapshot` 看到当前真实状态，无需重放历史事件。
- 手表会将 pending 请求和 outbox 保存在本地，断网时展示缓存；恢复连接后以 snapshot 为准。
- `request.resolved` 丢失时，重连快照会补齐。

## 9. 重连、心跳与后台

- 手表使用 OkHttp WebSocket，协议层 ping 间隔 25 秒；服务端必须按 WebSocket 规范自动回复 pong。
- 断线后指数退避重连：约 1s、2s、4s、8s、16s、30s（带少量抖动）。
- 网络恢复时立即尝试重连。
- 认证失败不自动重连。
- 手表用 `dataSync` 前台服务维持连接，通知栏常驻“Agent 在线”。Android 15 及以上将 dataSync 前台服务限制为每 24 小时累计 6 小时；超时后服务停止，需要重新打开应用恢复连接。
- 服务端应设置连接空闲/心跳超时，清理僵尸连接。

## 10. 服务端实现清单

1. 在 Upgrade 阶段校验 Bearer Token，失败返回 401/403。
2. 校验 `hello.protocol == 1`。
3. 为每个手表连接分配会话；每个设备单独记录 ack cursor。
4. 每次连接发送 `hello.ok`，紧接着发送完整 `snapshot`。
5. 维护全局递增 `seq`；所有状态事件广播时携带。
6. 维护 pending 表：请求 ID、请求体、过期时间、等待中的插件回调。
7. 收到 `request.respond`：校验、幂等落库、移除 pending、广播 `request.resolved`、唤醒插件。
8. 定时扫描过期请求，广播 `expired`，插件按拒绝处理。
9. 广播任务完成、Agent 状态变化。
10. 支持多手表连接与决定广播。
11. 日志中不要记录完整 Token；记录请求内容时注意脱敏。
12. 限流：限制每设备连接数、消息速率、待处理请求数，防止状态被刷爆。
13. 对文本字段设上限：`title` 120 字、`detail` 4000 字、`context` 2000 字、`label` 24 字、选项最多 4 个。
14. 不要信任手表上报的状态；pending 和决策结果以服务端为准。

## 11. 版本与兼容性

- 当前协议版本为 `1`。
- 不兼容变更必须提升 `v`；手表会忽略 `v != 1` 的帧。
- 新事件类型、新字段可以在 v1 内增加；手表会忽略不认识的类型和字段。
- 已废弃字段不要复用不同语义。

## 12. 最小示例

仓库内提供无第三方依赖的 mock gateway：

```bash
cd tools/mock-server
TOKEN=dev-token node server.js
```

然后用测试客户端跑完整握手与审批流程：

```bash
node test-client.js
```

测试脚本会连接、握手、触发一个高风险权限请求、提交 `allow_once`，并验证收到 `request.resolved`。
