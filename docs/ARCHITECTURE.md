# Pocket Agent — Android 本地 Agent 架构设计

> 参考本机 Claude-Code 风格 harness 的分层：**Provider / AgentLoop / ToolRegistry / PermissionBroker /
> ContextManager / Skills / MCP / Plugins / Timeline UI**，压缩到单进程 Android App 内运行。

## 0. 约束与事实（本机实测）
| 项 | 值 |
|---|---|
| JDK | Temurin 17.0.20 |
| Gradle | 8.14.3 (系统级，`gradle` 命令) |
| Android SDK | `/root/android-sdk` platform android-36，build-tools 35.0.0 (arm64 aapt2) |
| 离线 Maven | `/root/maven/localMvnRepository`（AGP 8.11.0, Kotlin 1.9.22） |
| 网络 | Google Maven / Maven Central / aliyun 可达（可补拉 Compose） |

## 1. 分层总览

```
┌──────────────────────────────────────────────────────────────┐
│ UI  (Compose Material3)                                      │
│  TimelinePane · ComposerBar · ApprovalSheet · TerminalPane    │
│  FilesPane · SettingsPane(Providers/MCP/Skills/Plugins)       │
├──────────────────────────────────────────────────────────────┤
│ AgentRuntime                                                 │
│  AgentLoop ── ContextManager ── PermissionBroker ── EventBus  │
├──────────────────────────────────────────────────────────────┤
│ Providers            │ Capabilities                           │
│  LlmProvider SPI     │  ToolRegistry                          │
│  AnthropicProvider   │   read/write/edit/glob/grep/bash/todo   │
│  OpenAiCompatProvider│  SandboxFs (path jail) + Shell         │
│  SSE parser          │  TerminalSession                       │
│  CredentialStore     │  Skills · MCP · Plugins/HookBus        │
├──────────────────────────────────────────────────────────────┤
│ platform (Android 实现)  │  core (纯 Kotlin, 可在 JVM 单测)     │
│  AndroidFs / KeyStore    │  json · diff · sse · jail · loop    │
│  ProcessShell / Context  │  provider · tools · mcp · skills    │
└──────────────────────────────────────────────────────────────┘
```

**关键可验证性原则**：所有业务逻辑放在 `core/`，**零 `android.*` 依赖**；
Android 相关能力（文件根目录、密钥库、进程、通知）一律走接口注入。
于是可以用 `gradle :app:testDebugUnitTest` 在**宿主机 JVM** 上真实跑通
「Provider + AgentLoop + 工具 + 审批 + 压缩」全链路（含内嵌 HTTP 假服务器）。

## 2. 核心数据模型
```
Message(role: user|assistant|system, blocks: List<Block>)
Block = TextBlock | ReasoningBlock | ToolUseBlock(id,name,input) | ToolResultBlock(id,content,isError)
ChatRequest(model, system, messages, tools: List<ToolSpec>, reasoning: ReasoningEffort, maxTokens)
```

## 3. Provider 抽象
```kotlin
interface LlmProvider {
  val id: String
  suspend fun stream(req: ChatRequest): Flow<ProviderEvent>
  suspend fun listModels(): List<String>
}
```
`ProviderEvent` = `TextDelta | ReasoningDelta | ToolCallDelta | Usage | Stop(reason) | Failed`
- **AnthropicProvider** → `POST /v1/messages`，SSE `content_block_delta`；工具用 `tool_use` 块；
  推理强度 → `thinking.budget_tokens`（off/low/medium/high = 0/2k/8k/24k）。
- **OpenAiCompatProvider** → `POST /v1/chat/completions`，SSE `choices[].delta.tool_calls`（分片聚合）；
  推理强度 → `reasoning_effort`。
- 传输：`HttpEngine` 接口；Android/JVM 用 `HttpURLConnection`，单测用假引擎/本地 `HttpServer`。
- 密钥：`CredentialStore` → Android Keystore(AES/GCM) 加密后存 SharedPreferences。

## 4. AgentLoop（多轮 + 工具循环）
```
append(user) → while(iter < maxIter):
   req = build(system+skills注入, context, tools, effort)
   stream(provider) → 文本/推理增量 → EventBus
   若 stop==tool_use: for each call:
        PermissionBroker.check() → 需审批则挂起并发 PermissionRequested(含 diff)
        批准 → Tool.execute() → ToolResult → 回填 context
   else break
触发 ContextManager.maybeCompact()（超阈值 → 摘要旧轮次）
```
`AgentEvent` 单向流驱动 UI 时间轴，UI 不持有任何循环状态（可配置换 ViewModel 重建）。

## 5. 沙箱
- **文件**：`PathJail` 把所有路径 `canonicalize` 后强制前缀 == workspaceRoot；
  拒绝 `..`、绝对路径越界、符号链接逃逸（比对 realPath）。
- **命令**：`ProcessShell` → `ProcessBuilder("/system/bin/sh","-c",cmd)`，
  cwd 固定 workspace，超时强杀，输出截断（默认 8KB/流），环境变量最小化。
- **网络**：仅 Agent 自身出网；工具不额外开网络（bash 受 Android 沙箱限制）。

## 6. 审批与 Diff
`PermissionBroker` 策略：`read/glob/grep/todo` 自动允许；`write/edit/bash/mcp/plugin` 默认询问。
- 每工具可配 `always|ask|never`，支持「本次会话记住」。
- `DiffEngine`（LCS，纯 Kotlin）为 `edit`/`write` 生成 unified diff，UI 用红绿行渲染后由用户批准。

## 7. 技能 / MCP / 插件
- **Skills**：`skills/<name>/SKILL.md`（YAML frontmatter: name/description/allowed-tools）→ 摘要在系统提示，
  正文按需 `skill_load` 注入（渐进披露）。
- **MCP**：JSON-RPC 2.0；transport = `Stdio(子进程)` 或 `Http(SSE)`；
  `initialize → tools/list → tools/call`，远端工具桥接为本地 `Tool` 注册进 `ToolRegistry`。
- **Plugins**：`plugins/<name>/plugin.json` 声明 `hooks / commands / tools`；
  `HookBus` 支持 `onUserPrompt / onToolCall(可改写/否决) / onToolResult / onStop`。

## 8. UI 时间轴
`LazyColumn` 渲染 `TimelineItem`：用户气泡 / 助手文本 / 可折叠推理 / 工具卡片(可展开输出) /
diff 审批卡 / 错误卡 / 压缩分隔线。底部 composer 含：模型选择、推理强度分段控件、发送/停止。
顶部 Tab：Chat · Terminal · Files · Settings。

## 9. Loop Engineering 验证计划
| Loop | 目标 | 验证手段 |
|---|---|---|
| 0 | 工具链冒烟：空 Compose App 可编译 | `gradle :app:assembleDebug` |
| 1 | json / diff / pathjail | JVM 单测 |
| 2 | 模型 + SSE 解析 + 两个 Provider | 内嵌 HttpServer 假端点 + 流式断言 |
| 3 | 工具集 + 沙箱 | 单测 + 真实 `/bin/sh` 集成测试 |
| 4 | 审批 + 上下文压缩 | 单测 |
| 5 | AgentLoop 端到端 | 假 LLM 脚本化 tool_use 全链路 |
| 6 | Skills + Plugins | 单测 |
| 7 | MCP JSON-RPC + 桥接 | 单测 + stdio 假服务器 |
| 8 | Android 平台层 | 编译 + 单测（接口 fake） |
| 9 | Compose UI | 编译 + `assembleDebug` |
| 10 | 全量收口 | `assembleDebug` + 全测试 + lint |

## 10. Android 平台层（Loop 8）

`core/platform` 只放接口与纯 Kotlin 逻辑（可在宿主 JVM 全量单测），`platform/` 放 Android 实现，
`AppContainer` 把两者装配起来：

| 抽象 | Android 实现 | 说明 |
|---|---|---|
| `KeyValueStore` | `AndroidKeyValueStore` | SharedPreferences（`pocket.agent`）；`apply()` 写入，进程内立即可读 |
| `SecretCipher` | `AndroidKeystoreCipher` | AndroidKeyStore AES-256/GCM；IV 由 Keystore 随机生成，blob = Base64(iv‖ct‖tag) |
| `HttpEngine` | `UrlConnectionHttpEngine` | 复用 JVM 实现（`java.net` 在 Android 上可用），已处理取消与 gzip |
| 组装根 | `AppContainer` | `filesDir` 作为 appRoot；`runtime` 惰性构造，冷启动不碰磁盘/Keystore |
| 进程单例 | `PocketAgentApp` | `by lazy` 持有 container，`onCreate` 不做 I/O |

安全要点：
- `android:allowBackup="false"`：密文即便被备份也离开不了设备（Keystore 密钥不随备份迁移）。
- skills / plugins 位于 `filesDir/.pocket` 而非 `workspace/`，Agent 无法给自己加权限。
- `approver` / `summarizer` 缺省为 null 时是**安全降级**：需审批的工具一律被拒，而不是静默放行。

### 进度
| Loop | 状态 |
|---|---|
| 0–7 | ✅ 341 tests |
| 8 | ✅ 379 tests · `:app:assembleDebug` 产出 APK |
| 9（Compose UI） | ✅ 475 tests · APK 14.5 MB（见 §11） |
| 10（全量收口） | ✅ `assembleDebug` + 475 tests + lint（0 errors / 11 warnings / 2 hints） |

## 11. Compose UI（Loop 9）

`MainActivity → AgentViewModel → RootScaffold`，底部四个 Tab；审批卡挂在导航**之上**，
因此切页不会丢掉挂起的请求，系统返回键等同「拒绝这一次」。

| 层 | 文件 | 职责 |
|---|---|---|
| 装配 | `AgentViewModel` | `viewModelScope` 承载会话，旋屏不断流；`onCleared` 解绑审批中继（fail-closed） |
| 外框 | `ui/RootScaffold.kt` | 底部导航 + 全局横幅（未配 Key 提示）|
| 对话 | `ui/chat/{ChatScreen,ComposerBar,ApprovalDialog}.kt` | 时间轴 + 输入条 + 审批卡（diff 高亮） |
| 状态机 | `ui/chat/ChatController.kt` | `AgentEvent` → 时间轴归约；**一个会话只有一个 `AgentLoop`**，跨轮复用 |
| 时间轴 | `ui/timeline/*` | 不可变条目 + 归约器 + 工具摘要（JVM 可测） |
| 设置 | `ui/settings/SettingsPane.kt` | provider / 模型 / 强度 / 轮次上限 / 工具策略 / MCP |
| 文件 · 终端 | `ui/files/*`、`ui/terminal/*` | workspace 内浏览与预览；命令历史、注入 exec |

三条不变量：
1. **会话粒度**：历史与「本会话始终允许」都活在 `AgentLoop` 里，所以 `send()` 复用同一个 loop；
   设置变更走 `rebindSession()` 重建并 `restore()` 接回历史 —— 「改完下一句就生效」且「对话不中断」。
2. **取消语义**：`runToken` 是代际号，停止 / 新建会话先自增再取消，被取消那轮的 `finally`
   不会误清新一轮的 `running`。
3. **UI 与 core 解耦**：`ChatController` 不依赖任何 `android.*` / `compose.*`，因此
   「发送 → 流式 → 工具 → 审批 → 收尾」全链路可在宿主 JVM 上用脚本化 LLM 端到端单测。
