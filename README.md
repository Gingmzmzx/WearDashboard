# Watch Agent Dashboard

方形 Android 手表和手机上的 AI Agent 面板。设备通过一条 WebSocket 长连接接收 Agent 的权限请求、提问和任务结果，并把用户的允许/拒绝决定实时回传。

同一 APK 自适应两种布局：手机自动使用控制台式卡片界面（快速审批、分组列表），手表使用大触控目标的方形表盘界面。服务端是独立的 Python + FastAPI 网关，Agent 插件/DeepSeek harness 通过 REST API 接入。

当前版本只实现 AI Agent 面板这一项功能，但项目结构可以直接扩展其他面板（日程、消息、健康等）。

## 功能

- 实时 Agent 状态：在线/重连/认证失败、各 Agent 工作中/等待/空闲/出错
- 待处理列表：权限请求、问题选项，按过期时间排序
- 权限审批：允许/拒绝；高风险操作必须长按 1 秒确认
- 问题回答：服务端提供 2 到 4 个选项
- 任务完成/失败/取消通知与最近完成列表
- 断线重连、快照恢复、待发送决定 outbox（防止审批丢失）
- 后台连接：前台服务 + 常驻通知
- 系统通知动作：普通请求可直接允许/拒绝；高风险请求只能打开应用后长按
- Token 用 Android Keystore AES-GCM 加密保存
- 方形表盘 UI：深色、大触控目标、可滚动
- 手机版 UI：状态总览、待处理快速操作、Agent 列表、最近完成、分组设置页
- FastAPI Gateway：SQLite 持久化、设备 Token、插件 REST API、长轮询、审计、自动过期

## 环境要求

- Android 手表或手机，Android 7.0 (API 24) 及以上；手表为方形表盘
- 允许 ADB 安装或未知来源安装
- 构建：JDK 17 或更高、Android SDK 36.1
- 服务端：Python 3.11+（推荐 3.12）
- 仅调试旧 mock gateway：Node.js 22+

本项目按“通用 Android 侧载 APK”开发，没有增加 Wear OS 商店专用打包。一个 APK 同时包含手表布局和手机布局：设备未声明手表硬件/`UI_MODE_TYPE_WATCH` 且可用宽度至少 300dp 时使用手机布局，否则使用手表布局。两侧共享连接、存储、协议与通知逻辑。

## 构建与安装

同一 APK 安装到手表或手机：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

协议解析单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

如果没有配置 Java 环境，可以使用 Android Studio 自带的 JBR：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

启动应用后点右上角设置（或首次页面的“配置服务器”），填写：

- 服务器地址：`wss://your-gateway/agent`，局域网调试可用 `ws://192.168.x.x:8787/agent`
- 访问 Token：服务端签发给这台设备的 Bearer Token
- 设备名称：可选，用于服务端区分设备

保存后会自动连接。连接状态、待处理数量会显示在常驻通知中。

## 手机版

不需要单独构建。手机横向空间足够时自动启用：

- 顶部控制台标题、连接状态与设置入口
- 汇总卡：待处理数量 / 一切正常 / 连接失败
- 待处理卡片：内联“允许/拒绝”或“打开处理”；高风险请求仍需进入详情长按
- Agent 状态列表与最近完成时间线
- 手机设置页：连接状态、端点表单、设备信息、通知与安全说明、断开连接

手机版快速操作会先更新本地 outbox，再把决定发给 Gateway；断线时按钮禁用并显示离线状态。审批详情与手表共用同一套协议语义。

### 用 adb 预配置

在设备上输入长 URL 很麻烦，可以用 adb 直接注入配置：

```bash
adb shell am start -n com.netessx.watchdashboard/.ui.MainActivity \
  --es server_url "ws://192.168.1.10:8787/agent" \
  --es token "dev-token" \
  --es device_name "My Watch"
```

注意：adb 命令行参数会出现在 shell 历史/进程信息中。正式 Token 优先使用设置页输入。

## 启动真实服务端

```bash
cd server
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# 修改 API_TOKEN / WATCH_TOKEN
uvicorn app.main:app --host 0.0.0.0 --port 8787
```

- 健康检查：`http://127.0.0.1:8787/healthz`
- 插件 API 文档：`http://127.0.0.1:8787/docs`
- 手机/手表地址：`ws://<电脑局域网 IP>:8787/agent`
- 设备 Token：`WATCH_TOKEN`，或通过 `POST /api/v1/devices` 为每台设备单独签发

服务端测试：

```bash
cd server
source .venv/bin/activate
pytest -q
```

详细部署、REST 示例、Docker、插件客户端见 [server/README.md](server/README.md)。插件可直接复制 [`server/examples/agent_client.py`](server/examples/agent_client.py)；DeepSeek harness 按 [服务端集成指南](docs/INTEGRATION.md) 写插件即可。

## 本地 UI 联调（mock gateway）

无需 FastAPI 服务端即可体验界面：

```bash
cd tools/mock-server
TOKEN=dev-token node server.js
```

设备连接 `ws://<电脑局域网 IP>:8787/agent`，Token 填 `dev-token`。在 mock gateway 终端输入：

| 命令 | 效果 |
| --- | --- |
| `p` | 高风险权限请求（验证长按审批） |
| `l` | 普通权限请求（验证通知直接审批） |
| `q` | 三选项问题 |
| `t` | 成功任务完成 |
| `f` | 失败任务完成 |
| `s` | 切换 Agent 状态 |
| `e` | 让最早请求过期 |

也可以不连接设备，直接跑协议探测：

```bash
cd tools/mock-server
node test-client.js
```

## 协议与文档

- [协议规范 v1](docs/PROTOCOL.md)：传输、认证、信封、事件、幂等、超时、安全要求
- [FastAPI Gateway](server/README.md)：部署、设备 Token、REST API、插件示例、Docker
- [服务端与 Agent 插件集成指南](docs/INTEGRATION.md)：Gateway 状态机、内部 API、插件映射、上线清单

核心设计：

- WebSocket + JSON 信封：`v / id / seq / type / ts / payload`
- 每次连接都下发 `snapshot`，客户端以快照为准，不依赖历史事件重放
- 客户端持久化 outbox，断线重连后重发未确认的决定；服务端必须幂等
- `seq` 去重、乐观展示、服务端为超时唯一权威
- 高风险审批默认拒绝，过期即拒绝

## 目录结构

```
app/src/main/java/com/netessx/watchdashboard/
├── App.java                     Application、通知通道、前台可见性
├── data/
│   ├── AgentStore.java          状态快照、持久化、outbox、去重
│   ├── Settings.java            地址/Token/设备配置
│   ├── SecurePrefs.java         Keystore AES-GCM
│   ├── AgentInfo.java            Agent 状态模型
│   ├── AgentRequest.java         权限/问题模型
│   ├── AgentOption.java          选项模型
│   └── TaskResult.java           任务结果模型
├── net/Protocol.java            协议编解码
├── service/
│   ├── AgentConnectionService.java  WebSocket、重连、前台服务
│   └── NotificationPresenter.java   通知与通知动作
└── ui/
    ├── MainActivity.java        单 Activity，自适应手表/手机
    ├── DashboardScreen.java     手表状态总览
    ├── PhoneDashboardScreen.java 手机控制台与快速操作
    ├── RequestScreen.java       审批/提问详情（自适应尺寸）
    ├── SettingsScreen.java      手表配置
    ├── PhoneSettingsScreen.java 手机配置
    ├── UiKit.java               UI 基础组件
    └── HoldToApproveButton.java 高风险长按组件

docs/                          协议与集成文档
server/                        FastAPI Gateway、测试、插件客户端、Docker
tools/mock-server/             无依赖 mock gateway + 协议测试客户端
```

## 安全设计

- 手表 Token 具备审批能力，必须按高敏凭据管理；建议每设备独立、可吊销。
- 正式环境使用 `wss://`。当前为了局域网调试允许 `ws://`，明文会泄露 Token 和审批内容。
- Token 使用主动生成的 Keystore AES-256-GCM 密钥加密，存入 SharedPreferences 的只有密文。
- 高风险请求在详情页需要长按 1 秒；系统通知不提供高风险“允许”动作。
- 通知内容使用 `VISIBILITY_PRIVATE`，锁屏时隐藏正文。
- 服务端必须执行最终校验：请求是否过期、选项是否合法、重复决定是否幂等。设备时钟不可信。
- FastAPI Gateway 默认使用单 worker + SQLite；生产环境请在前面加 TLS 反向代理，并通过 `POST /api/v1/devices` 为每台设备签发独立 Token。

## 常见问题

- 连接失败：确认手表与服务端在同一网络、地址端口可达，Token 与 `Authorization: Bearer` 一致。`ws://` 仅适合局域网调试。
- 屏幕熄灭后连接断开：在手表系统设置中关闭该应用的电池优化/后台限制。部分手表 ROM 仍会在深度休眠时断网。
- 通知不出现：检查应用通知权限与“Agent 请求”通知渠道是否开启。高风险请求的通知不提供直接“允许”按钮。
- `wss://` 握手失败：服务端证书必须被手表系统信任。自签名证书需要先在系统中安装信任链，开发阶段可临时使用局域网 `ws://`。

## 已知限制

- 问题只支持服务端给定的选项，暂不支持手表/手机文字或语音输入。
- 最近完成任务最多保留 10 条；完整历史应由服务端提供。
- 仅支持一个 Gateway 地址、一个 Token。
- Gateway 为单进程单 worker；多实例需要 Redis/NATS 广播和 PostgreSQL。
- 无 FCM/推送回落：前台服务被系统杀掉后，直到再次打开应用才会恢复连接。
- Android 15+ 对 `dataSync` 前台服务限制为每 24 小时累计 6 小时；超时后服务会停止并在界面提示，重新打开应用可恢复。部分国产手表 ROM 还可能更激进地杀后台。
- 未包含 Wear OS Play 商店打包（Wear 清单、Wear 图标、分发配置）。

## 下一步扩展

- 自由文本/语音快速回复
- 手机版推送（FCM）回落
- 多 Gateway 或每 Agent 独立连接
- 历史事件时间线
- 请求批量处理与通知分组
- 按 Agent 配置不同的审批策略（如低风险自动通过）
