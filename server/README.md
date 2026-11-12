# Watch Agent Gateway (FastAPI)

手表/手机端协议 v1 的生产型 Gateway。它同时提供：

- 给手表/手机应用的 WebSocket 接口：`/agent`
- 给 Agent 插件 / DeepSeek harness 的 REST API
- SQLite 持久化、设备 Token、待处理请求、审计记录
- 请求超时自动拒绝、多设备广播、断线快照恢复
- OpenAPI 文档：启动后访问 `/docs`

WebSocket 协议细节见 [../docs/PROTOCOL.md](../docs/PROTOCOL.md)。本服务端是协议 v1 的参考实现。

## 架构

```
Agent 插件 / DeepSeek harness
        │  REST（API_TOKEN）
        ▼
┌──────────────────────────────┐
│ FastAPI Gateway              │
│  · SQLite（请求/任务/设备）   │
│  · 过期扫描 + 审计            │
│  · WebSocket Hub             │
└──────────────┬───────────────┘
               │ WebSocket 协议 v1（设备 Token）
        ┌──────┴──────┐
        ▼             ▼
      手表           手机
```

插件在敏感操作前调用 `POST /api/v1/requests`，然后长轮询等待决定。手表/手机收到事件、用户点击允许或拒绝，Gateway 广播 `request.resolved` 并把结果返回给插件。

## 快速开始

要求 Python 3.11+（推荐 3.12）。

```bash
cd server
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# 编辑 .env，至少修改 API_TOKEN 与 WATCH_TOKEN
# app 启动时会自动加载 server/.env

uvicorn app.main:app --host 0.0.0.0 --port 8787
```

检查：

```bash
curl http://127.0.0.1:8787/healthz
```

- API 文档：`http://<host>:8787/docs`
- WebSocket：`ws://<host>:8787/agent`
- 数据库默认写到 `data/gateway.db`
- 请保持 **单 worker**（默认就是）；内存中的 WebSocket Hub 不支持多 worker 横向扩展。

## 两类 Token

| Token | 用途 | 传递方式 |
| --- | --- | --- |
| `API_TOKEN` | 插件调用 REST API | `Authorization: Bearer ...` 或 `X-API-Key` |
| 设备 Token | 手表/手机连接 WebSocket | `Authorization: Bearer ...` |

- `WATCH_TOKEN` 只用于首次启动时播种一个设备，方便本地联调。
- 正常使用应为每台设备单独签发 Token：

```bash
export API_TOKEN='你的 API_TOKEN'

curl -s -X POST http://127.0.0.1:8787/api/v1/devices \
  -H "Authorization: Bearer $API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"My Watch"}'
```

响应中的 `token` 只返回一次，填到手表/手机设置页。设备 Token 可单独吊销；吊销时活动连接会被关闭。

## 手机/手表配置

在应用设置页填写：

- 服务器地址：`ws://<电脑局域网 IP>:8787/agent`（生产用 `wss://`）
- 访问 Token：设备 Token
- 设备名称：任意

Android 模拟器访问宿主机用 `ws://10.0.2.2:8787/agent`；真机用电脑的局域网 IP。应用会自动选择手表布局或手机布局，两者使用同一 APK。

## 插件 REST API

所有 `/api/v1/*` 接口需要 API Token。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/api/v1/requests` | 创建权限请求或提问 |
| GET | `/api/v1/requests/{id}` | 查询状态与决定 |
| GET | `/api/v1/requests?status=pending` | 列出请求 |
| GET | `/api/v1/requests/{id}/wait?timeout=30` | 长轮询等待决定 |
| POST | `/api/v1/requests/{id}/cancel` | 取消待处理请求 |
| POST | `/api/v1/agents/{agentId}/status` | 更新 Agent 状态 |
| POST | `/api/v1/tasks` | 上报任务完成/失败/取消 |
| GET | `/api/v1/state` | 当前快照、连接数、待处理数 |
| POST | `/api/v1/devices` | 签发设备 Token |
| GET | `/api/v1/devices` | 列出设备 |
| DELETE | `/api/v1/devices/{id}` | 吊销设备 |
| GET | `/healthz` | 健康检查（无需鉴权） |

### 创建权限请求

```bash
curl -s -X POST http://127.0.0.1:8787/api/v1/requests \
  -H "Authorization: Bearer $API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "kind": "permission",
    "agentId": "claude-code",
    "agentName": "Claude Code",
    "title": "运行构建清理命令？",
    "detail": "rm -rf build && ./gradlew assembleDebug",
    "risk": "high",
    "ttlSec": 120,
    "options": [
      {"id": "allow_once", "label": "允许一次", "style": "approve"},
      {"id": "deny", "label": "拒绝", "style": "deny"}
    ]
  }'
```

响应：

```json
{
  "requestId": "req_...",
  "kind": "permission",
  "agentId": "claude-code",
  "agentName": "Claude Code",
  "title": "运行构建清理命令？",
  "detail": "rm -rf build && ./gradlew assembleDebug",
  "risk": "high",
  "question": "",
  "context": "",
  "createdAt": 1760000000000,
  "expiresAt": 1760000120000,
  "allowFreeText": false,
  "options": [
    {"id": "allow_once", "label": "允许一次", "style": "approve"},
    {"id": "deny", "label": "拒绝", "style": "deny"}
  ],
  "status": "pending",
  "optionId": null,
  "resolution": null,
  "resolvedAt": null,
  "resolvedByDeviceId": null
}
```

不传 `options` 时，权限请求默认使用 `allow_once` / `deny`。`question` 类型必须提供 `question` 与至少一个选项。

### 等待决定

```bash
curl -s "http://127.0.0.1:8787/api/v1/requests/<requestId>/wait?timeout=30" \
  -H "Authorization: Bearer $API_TOKEN"
```

- 请求已解决：立即返回，`status` 为 `answered`、`expired` 或 `cancelled`。
- 仍待处理：最多等待 `timeout` 秒后返回 `status: pending`，调用方继续轮询。
- `timeout` 最大 60 秒。

决定的含义：

| status | 插件行为 |
| --- | --- |
| `answered` | 执行 `optionId` 对应的动作 |
| `expired` | 按拒绝处理 |
| `cancelled` | 中止任务 |
| `pending` | 继续等待 |

**超时、网关不可达、连接异常都必须按拒绝处理，不能默认放行。**

### 更新状态与任务

```bash
curl -s -X POST http://127.0.0.1:8787/api/v1/agents/claude-code/status \
  -H "Authorization: Bearer $API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Claude Code","status":"working","detail":"Refactoring","task":"Auth middleware"}'

curl -s -X POST http://127.0.0.1:8787/api/v1/tasks \
  -H "Authorization: Bearer $API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "agentId": "claude-code",
    "agentName": "Claude Code",
    "title": "重构登录中间件",
    "summary": "4 个文件更新，测试全部通过",
    "outcome": "success",
    "durationMs": 184000
  }'
```

带 `taskId` 的重复上报是幂等的，不会重复广播。

## 插件客户端

[`examples/agent_client.py`](examples/agent_client.py) 是可直接复制进 harness 的异步客户端。用法：

```python
from agent_client import GatewayClient

async def run_agent():
    async with GatewayClient(
        "http://127.0.0.1:8787",
        api_token="...",
        agent_id="claude-code",
        agent_name="Claude Code",
    ) as gw:
        await gw.set_status("working", detail="Planning", task="Refactor auth")

        decision = await gw.ask_permission(
            "运行构建清理命令？",
            detail="rm -rf build && ./gradlew assembleDebug",
            risk="high",
            ttl_sec=120,
        )
        if decision != "allow_once":
            return "denied"

        # ... execute the action ...
        await gw.complete_task("重构登录中间件", summary="测试全部通过")
        await gw.set_status("idle")
```

`ask_permission` 在超时或请求未解决时返回 `"deny"`。

### 给 DeepSeek harness 的接入说明

插件负责 Agent 循环，Gateway 只负责人工审批。建议：

1. 启动时用稳定 `agentId` 注册一次 `working` 状态。
2. 在拦截到高风险工具调用（shell、写文件、网络、支付、发布）时调用 `create_permission` / `ask_permission`。
3. 用返回的 `optionId` 决定执行、缩小范围或中止；`deny` 必须真的阻止工具调用。
4. 工具执行前后调用 `set_status`（`working` / `waiting` / `idle` / `error`）。
5. 任务结束调用 `complete_task`，附简短 summary；不要上传完整日志。
6. 把 `/docs` 的 OpenAPI 交给插件生成代码，或直接复制 `agent_client.py`。
7. 服务端不可达时按拒绝处理，并降级为人工在终端确认；不要让审批失败变成自动放行。

完整可运行示例见 [`examples/demo_flow.py`](examples/demo_flow.py)：

```bash
export GATEWAY_URL=http://127.0.0.1:8787
export API_TOKEN=dev-api-token
python examples/demo_flow.py
```

它会创建高风险审批、等待手表决定、上报任务完成。可以先用 [mock gateway](../tools/mock-server/) 测试手表 UI；接插件时改用本服务端。

## 数据与可靠性

- SQLite 表：`requests`、`tasks`、`agents`、`devices`、`decisions`、`counters`
- 每次状态变更分配单调递增 `seq`，断开重连靠完整 `snapshot` 恢复。
- 决定使用条件更新，保证同一请求只有第一个决定生效；重复提交幂等。
- 每个决定写入 `decisions` 审计表，记录设备与时间。
- 服务端是超时的唯一权威；到期后广播 `request.resolved` 并返回 `expired`。
- 有多种传输/并发限制时建议在反向代理层加限流。
- 备份：定期复制 `data/gateway.db`（WAL 模式下同时复制 `-wal`/`-shm` 或先停服务）。

## 测试

```bash
cd server
source .venv/bin/activate
pytest -q
```

测试覆盖：握手/快照、权限事件与决定、幂等、过期、长轮询、任务/Agent 事件、设备 Token 生命周期、鉴权。

## Docker

```bash
cd server
cp .env.example .env
# 修改 .env
docker compose up -d --build
```

数据卷映射到 `server/data`；容器内使用 `/data/gateway.db`。保持单 worker。

## 生产部署建议

- 必须使用 HTTPS/WSS。用 Caddy/Nginx 终止 TLS 并反代到 `127.0.0.1:8787`，不要在公网直接暴露明文 HTTP。
- 使用强随机 `API_TOKEN`、`WATCH_TOKEN`；为每台设备单独签发 Token，丢失时吊销。
- 高风险审批的“长按确认”由客户端执行；服务端只校验选项合法性。需要更强保证时，可只给高风险请求下发 `allow_once` / `deny`，或在插件层再次校验命令。
- 限制 `/api/v1/*` 的来源网络（VPN、零信任网关或防火墙白名单）。
- 定期备份 SQLite；关注 `/healthz`。
- 多实例部署需要把 WebSocket Hub 换成 Redis/NATS 广播，并改用 PostgreSQL；当前版本不包含。
- 不要记录完整 Token、会话内容或敏感命令到日志。应用侧通知默认锁屏隐藏正文，但这不能替代服务端的最小化传输。

## 当前限制

- 单进程、单 worker；SQLite 写入量适合个人/小团队。
- 不支持自由文本回答，问题只支持预设选项。
- 请求数量/每 Agent 待处理数没有硬上限；如需公网多租户，请在网关前加限流与配额。
- 不包含 Web 管理界面，使用 `/docs` 或 REST API 管理。
