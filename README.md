<div align="center">

<img src="docs/icon.svg" width="112" alt="Handy Agent">

# Handy Agent

**跑在手机上的本地 Agent，能操作这台手机本身。**

对标 computer use 的 mobile use · 模型直连，不依赖电脑 · 逐动作审批

[![Release](https://img.shields.io/github/v/release/MikhailTail/handy-agent?color=96442B)](https://github.com/MikhailTail/handy-agent/releases)
[![License](https://img.shields.io/badge/license-MIT-96442B)](LICENSE)
![Android](https://img.shields.io/badge/Android-11%2B-96442B)

</div>

---

## 它是什么

桌面版 cc-haha 的能力被绑死在桌面宿主里 —— 真正的 Agent 是个子进程，文件系统、终端、浏览器、Computer Use 全靠 Windows / macOS 的原生能力。它的 H5 手机端只是把同一份网页用手机浏览器打开，**没有也无法提供 mobile use**。

Handy Agent 走的是相反的路：**Agent 的大脑跑在手机上**，模型直连，不经过任何电脑。它通过 Android 无障碍服务"看见"屏幕上的控件，并代替你点击、滑动、输入。

所以它不是"把桌面 Agent 搬到手机"，而是**在 Android 上重建一个本地 Agent，并新增 mobile use**。

内核移植自 [pocket-agent](https://github.com/MikhailTail/pocket-agent)，UI 与 mobile use 全新。

## mobile use

Agent 能真正操作这台手机。感知、操作、应用级控制，全部在审批之下。

<table>
<tr><td width="120"><b>感知</b></td><td>

`mobile_ui_tree` **一次调用同时返回当前屏幕截图 + 带序号的控件清单**。

截图负责"看清内容" —— 图标、图片、颜色、没有文字标签的区域。控件清单负责"精确选中" —— 用序号而不是猜坐标。**两者缺一不可**：只用树看不见图标，只用截图只能靠猜。

</td></tr>
<tr><td><b>操作</b></td><td>

`mobile_tap` / `mobile_swipe` / `mobile_type` / `mobile_key`

点击优先用序号 + `dump_id` 定位，坐标只作兜底。每一步都重新 dump 界面树 —— 序号对不上就报错让模型重新观察，**宁可失败也不猜**（误触在手机上可能是转账或发消息）。

中文输入走三级降级：`ACTION_SET_TEXT` → 自带输入法 `commitText` → 剪贴板粘贴，每级写完都重读校验。

</td></tr>
<tr><td><b>应用级</b></td><td>

`mobile_launch` 启动应用 · `mobile_notifications` 读取通知 · `mobile_state` 查看前台应用与各项能力状态

</td></tr>
<tr><td><b>护栏</b></td><td>

9 个 mobile 工具里 4 个只读、5 个写操作**逐动作弹审批卡**。审批卡把 JSON 翻译成人话：

> 点击 [12] BUTTON「发送」（当前应用 com.tencent.mm）

遇到 `FLAG_SECURE` 界面（银行、支付）自动硬停并交还给你。同屏打转超过阈值会提示模型换策略。

</td></tr>
</table>

## 多模态

- `Block.Image` + `ToolResult.images`，Anthropic 与 OpenAI 兼容两个 Provider 都能收发图片
- **截图只存文件名与元数据（`ImageRef`），base64 不驻留内存与会话文件**
- JPEG q80、长边 1280 只缩不放、单图 ≤400KB，超限自动降档重压
- `TokenEstimator.estimateImage` 按各家的像素规则估算占用

## 多会话与多工作区

- **工作区**：每个工作区有独立的文件沙箱与会话集，互不干扰
- **会话**：持久化、切换、删除、重命名；落盘走原子写，索引损坏会自动扫目录重建
- fork 会吸附到合法的轮次边界，不会切开 `tool_use` / `tool_result` 配对
- 老版本目录布局自动迁移

## 扩展

设置页可配 **MCP 服务端**（JSON），Skills 与 Plugins 沿用 pocket-agent 的机制。

## 界面

取自 cc-haha 的设计令牌，六套主题：**纸墨**（默认）、纯白、经典暖色、青瓷、墨夜、墨夜蓝。

两条设计主张：靠底色分层与 1px 细边框建立层次，**不靠重阴影**；主按钮是墨色实心，陶土红只用于强调。

图标是甲骨文的「手」—— 三根手指直接当三个点，远看是「手」的象形，近看那三点又读得出「思考中」。

## 安装

从 [Releases](https://github.com/MikhailTail/handy-agent/releases) 下载 APK。需要 **Android 11+**。

装好后还有两步，否则 mobile use 不会工作：

1. **设置 → 无障碍 → Handy Agent 设备操作** → 打开
2. **Android 13+ 且是侧载安装**：应用信息页右上角 **⋮ → 允许受限设置** → 生物识别确认

第 2 步是侧载应用的最大门槛 —— 不打开的话无障碍开关会被系统置灰。应用内有「能力体检」页会逐步引导，并实时复检每一项。

## 构建

```bash
# JDK 17 + Android SDK 36
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
```

跑单测：

```bash
./gradlew :app:testDebugUnitTest
```

**676 个用例全绿。** 全部是宿主 JVM 单测，不需要设备 —— `core/` 不含任何 `android.*` 依赖，`core/mobile/` 的坐标计算、序号解析、敏感界面判定、打转检测都是纯函数，可以直接断言。

想发 release 签名包的话，在仓库根目录建 `keystore.properties`（格式见 `keystore.properties.example`）。**这个文件已被 .gitignore 排除，不会入库。**

## 项目结构

```
app/src/main/java/dev/mikhailtail/handyagent/
├── core/                   纯 Kotlin，零 android.* 依赖，宿主 JVM 全可测
│   ├── model/              Message / Block / ImageRef
│   ├── loop/               AgentLoop：工具调用循环、tool_use 配对校验
│   ├── provider/           Anthropic / OpenAI 兼容 + SSE
│   ├── tool/               bash, read, write, edit, glob, grep, ls, todo
│   ├── mobile/             UiTreeFlattener, GesturePlanner, ActionResolver,
│   │                       SensitiveScreenDetector, LoopDetector
│   ├── permission/         审批 Broker / Previewer
│   ├── sandbox/            PathJail：文件沙箱逃逸防护
│   ├── platform/           AgentRuntime 组装根、SessionStore、WorkspaceLayout
│   └── mcp/ skill/ plugin/ 扩展机制
├── platform/mobile/        Android 实现：无障碍服务、通知监听、图片编解码
└── ui/                     Compose 界面
```

**分层原则**：`core/` 不依赖 `android.*`，平台能力通过接口注入。所以绝大部分逻辑能在电脑上跑测试，装机只用来验证真正需要设备的部分（手势坐标、截图三态、中文输入、后台保活）。

## 已知限制

- 无障碍被系统关闭后**无法用代码重新打开**（需要 `WRITE_SECURE_SETTINGS`），只能检测 + 提示，不假装能自愈
- 部分 OEM（HyperOS / MIUI）会杀后台无障碍服务，需要手动加白名单
- Android 13+ 侧载包必须手动开「允许受限设置」，这是系统限制，没有绕过的办法
- 定时任务、分享入口、自带输入法尚未实现
- 图片与通知含隐私：全部落在私有目录，`allowBackup=false`，base64 绝不进日志

## License

[MIT](LICENSE)
