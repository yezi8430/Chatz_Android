# Chatz

Android 上的自托管消息推送客户端。一台 App 里可以同时挂载多台服务器，同一套界面管理所有消息。

支持的服务端：

- **Chatz** —— 原生支持，频道、已读状态、归档、未读计数、消息聚合、标签、设备与路由规则全量可用
- **Gotify** —— 兼容其 HTTP + WebSocket 协议
- **ntfy** —— 按主题订阅

> 本客户端只做「接收与管理消息」，消息从哪来由你自己的服务器决定。不经过任何第三方中转服务。

## 特性

**多服务器**

- 以「服务卡」的形式管理多台服务器，互不干扰，可单独停用
- 添加服务器时自动探测类型（请求 `/config` 判定是否为 Chatz，探测不到按 Gotify 处理）
- 自定义显示名，保活间隔自动（Wi-Fi 45s / 移动网络 180s）

**频道（Chatz）**

- 频道发现、订阅、退订
- 频道密码、频道图标
- 频道级免打扰

**消息**

- Markdown 渲染、图片与附件预览
- 已读 / 未读、全部标已读、归档 / 取消归档
- 全文搜索（Room FTS）
- 分页加载（Paging 3）
- 消息聚合折叠、标签、发送者角色徽标（超级管理员 / 管理员）
- 直接回复、草稿保留、气泡通知

**免打扰**

- 全局开关 + 按时段（起止时间、星期几）
- 按应用单独配置，也可只针对某个频道

**其它**

- 桌面小部件（显示最近消息）
- 主题色自定义、深色模式
- 开机自启、前台服务保活、网络变化自动重连
- 通知栏直回复

## 截图

| 消息列表 | 自定义主题色 |
|---|---|
| ![消息列表](docs/screenshots/ui1.jpg) | ![自定义主题色](docs/screenshots/ui2.jpg) |
| **抽屉与频道** | **设置** |
| ![抽屉与频道](docs/screenshots/DrawerContent.jpg) | ![设置](docs/screenshots/settings.jpg) |

## 下载

到 [Releases](../../releases) 页面下载最新的 `app-release.apk`，装完后在 App 里填你自己的服务器地址即可。

- 包名：`asia.guojuice.yezigotify`
- 最低系统要求：Android 7.0（API 24）及以上

## 快速开始

1. 安装 APK，首次打开授予通知权限
2. 「添加服务」→ 选类型（Chatz / Gotify / ntfy）
3. 填服务器地址与凭据：
   - **Chatz**：用户名 + 密码登录，或直接使用 Token
   - **Gotify**：应用 Token / 客户端 Token
   - **ntfy**：服务器地址 + 订阅主题（需要鉴权时填用户名密码）
4. 保存后自动连接，之后消息会实时推送

**Chatz 服务端**是配套的开源 Node.js 服务端，见 [github.com/yezi8430/Chatz](https://github.com/yezi8430/Chatz)。

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` | 连接你的服务器 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 判断网络类型，决定保活间隔与重连 |
| `POST_NOTIFICATIONS` | 发通知（Android 13+ 需授权） |
| `FOREGROUND_SERVICE(_SPECIAL_USE)` | 保持长连接 |
| `WAKE_LOCK` | 保活期间防止休眠断连 |
| `RECEIVE_BOOT_COMPLETED` | 开机后自动恢复连接 |
| `SCHEDULE_EXACT_ALARM` | 免打扰时段、定时保活 |
| `VIBRATE` | 通知震动 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 可选，避免系统省电策略掐断长连接 |

所有权限都只服务于「收消息」这一件事，没有位置、通讯录、通讯记录一类的权限。

## 隐私

- **没有任何统计、崩溃上报或遥测 SDK**，App 不会向除你自己服务器以外的任何地址发送数据
- 消息数据只存在本机 Room 数据库里
- 服务器地址与凭据用 AES‑256‑GCM 加密后存本地，密钥放在 Android Keystore（不可导出），并已关闭 `allowBackup`，不会进入云备份
- 权限与凭据的详细说明见 [PRIVACY.md](PRIVACY.md)

## 从源码构建

```bash
git clone <本仓库地址>
cd yezigotify
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。

- JDK 21、Android Studio 最新版即可打开
- `settings.gradle.kts` 里仓库顺序是阿里云镜像在前、`google()` / `mavenCentral()` 在后，国内构建快；境外的机器可以把官方仓库调到前面
- 发布签名流程见 [RELEASE.md](RELEASE.md)

## 技术栈

Kotlin · Jetpack Compose（Material 3）· Room 3 + FTS · Paging 3 · OkHttp / Retrofit · Gson · Markwon · Glide · Kotlin Coroutines

`minSdk 24` / `targetSdk 34` / `compileSdk 37`

## 许可证

[MIT](LICENSE)
