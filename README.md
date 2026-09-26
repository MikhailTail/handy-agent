# Pocket Agent

把 Claude Code 式的 Agent 循环塞进 Android 手机：多轮工具调用、写文件前弹 diff 审批、
路径沙箱、MCP / Skills / Plugins，全部在设备本地运行。

- **纯 Kotlin**，无原生库，一个 APK 通吃所有架构
- **无后端**：直连你自己配置的模型服务，密钥由 Android Keystore 加密后存在本机
- **无遥测**：应用只在你发起对话时出网

---

## 安装

从 [Releases](../../releases) 下载最新 APK 安装即可。

| 项 | 要求 |
|---|---|
| Android | 8.0 (API 26) 及以上 |
| 架构 | 通用（应用内无原生库，arm64 / arm32 / x86_64 均可）|
| 权限 | `INTERNET`、`ACCESS_NETWORK_STATE`，仅此两项 |

> 首次安装需在系统设置里允许「安装未知来源应用」。

## 快速开始

1. 打开 App → 底部 **Settings** → **Providers**
2. 选一个预置服务，填入你自己的 API Key：

   | 预置 | 默认模型 | 说明 |
   |---|---|---|
   | Anthropic | `claude-sonnet-4-5` | 官方 API |
   | OpenAI | `gpt-4o` | 官方 API |
   | DeepSeek | `deepseek-chat` | 官方 API |
   | Ollama (本地) | `qwen2.5:7b` | 指向 `127.0.0.1:11434`，手机本地跑模型 |

3. 回到 **Chat** 页开始对话

API Key 通过 Android Keystore 的 AES-256/GCM 加密后存放，明文不落盘。

## 它能做什么

**工具循环** — `read` / `write` / `edit` / `glob` / `grep` / `bash` / `todo`，
模型自主多轮调用直到任务完成，轮次上限可配。

**审批与 Diff** — `write` / `edit` / `bash` / `mcp` / `plugin` 默认需要你确认。
改动会渲染成红绿 unified diff，看清楚再批；不想每次都点可以设「本会话始终允许」。
读类工具（`read` / `glob` / `grep` / `todo`）自动放行。

**沙箱** — 文件路径经 `canonicalize` 后强制落在 workspace 内，`..`、绝对路径越界、
符号链接逃逸一律拒绝；`bash` 通过 `/system/bin/sh` 执行，工作目录固定在 workspace，
超时强杀，输出截断。

**上下文压缩** — 对话超过输入预算阈值（默认 85%）时自动摘要旧轮次，长会话不断。

**MCP** — JSON-RPC 2.0，支持 `Stdio`（子进程）与 `Http(SSE)` 两种 transport，
远端工具自动桥接进本地工具表。

**Skills** — 在 `<appRoot>/.pocket/skills/<name>/SKILL.md` 放一个带 YAML frontmatter
的 Markdown，摘要进系统提示，正文按需注入（渐进披露）。

**Plugins** — `plugin.json` 声明 hooks，支持在 `onUserPrompt` / `onToolCall`（可改写或否决）
/ `onToolResult` / `onStop` 插入逻辑。

**其他** — 内置终端、workspace 文件浏览、会话导出分享（`content://` 只能由你显式授权给目标 App）。

## 从源码构建

构建环境（与原项目开发环境一致）：

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.14.3 |
| Android Gradle Plugin | 8.11.0 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| build-tools | 35.0.0 |

```bash
# 1. 生成 gradle wrapper（首次；仓库未提交 wrapper jar）
gradle wrapper --gradle-version 8.14.3

# 2. 跑单元测试（475 个，全部在宿主 JVM 上跑，不需要设备）
./gradlew :app:testDebugUnitTest

# 3. 构建 debug 包
./gradlew :app:assembleDebug
```

### 构建签名的 release 包

release 构建从 `keystore.properties` 读签名信息（该文件已被 `.gitignore` 排除，不会入库）：

```bash
# 生成你的签名密钥（只需要做一次，务必备份 .jks 和密码）
keytool -genkeypair -v \
  -keystore pocket-agent-release.jks \
  -alias pocket-agent \
  -keyalg RSA -keysize 4096 -validity 10000
```

在仓库根目录建 `keystore.properties`：

```properties
storeFile=/绝对路径/pocket-agent-release.jks
storePassword=你的密码
keyAlias=pocket-agent
keyPassword=你的密码
```

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> **签名密钥丢了就再也无法给已安装用户推送更新**（只能让用户卸载重装）。请离线备份 `.jks` 与密码。

## 架构

分层设计、模块职责、Loop engineering 的验证计划，见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

一句话概括：**所有业务逻辑在 `core/` 且零 `android.*` 依赖**，Android 能力（文件根、密钥库、
进程、通知）全部走接口注入，因此 Provider + AgentLoop + 工具 + 审批 + 压缩的全链路
可以在宿主机 JVM 上真实跑通测试。

## 安全模型

| 面 | 做法 |
|---|---|
| API Key | Android Keystore AES-256/GCM 加密，blob = `iv‖ct‖tag` |
| 备份 | `allowBackup="false"`，密文即使被备份也解不开（密钥不随备份迁移）|
| 提权 | skills / plugins 放在 workspace **之外**，模型无法给自己加权限 |
| 失败姿态 | 审批器缺失时**一律拒绝**需审批的工具，而不是静默放行 |
| 文件 | `PathJail` 前缀校验 + 符号链接 realPath 比对 |
| 命令 | 超时强杀、输出截断、环境变量最小化 |

**已知取舍**：应用声明了 `usesCleartextTraffic="true"`，以便连接自建的 HTTP 服务
（如局域网 Ollama）。若你只在公网 HTTPS 服务上用，这一项对你是无感的；但请知悉它意味着
应用允许明文 HTTP 流量。

## 已知限制

- release 构建**未开启 R8 混淆**（0.1.0）。开启后包体更小，但需要重新验证运行时行为。
- 会话历史落盘在应用私有目录，卸载即清除。
- 项目未在 Google Play 上架，通过 Releases 分发。

## License

[MIT](LICENSE)
