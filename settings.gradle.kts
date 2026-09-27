// 仓库走国内镜像：官方源实测极慢（services.gradle.org 169 B/s、
// repo.maven.apache.org 38 KB/s），阿里云对应仓库快 2~80 倍。
// 官方源保留在末尾作为兜底（阿里云缺件时才会走到）。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}
rootProject.name = "handy-agent"

// 分层对齐 cc-haha：:server 复刻它的 HTTP+WS 服务端，:kernel 复刻 sidecar 的
// cli 内核。两者都刻意做成**纯 JVM 模块**（不含 android.*），于是绝大多数逻辑
// 能在电脑上直接跑单测，不必动模拟器 —— 这是移植期最重要的生产力来源。
include(":app")
include(":server")
// 转录与设置的落盘层：JSONL 是真源，索引可重建。
include(":persistence")
// Agent 内核。:kernel-api 放平台无关的契约，:kernel 放主循环 ——
// 两者都不含 android.*，于是能拿脚本化模型在电脑上直接跑单测。
include(":kernel-api")
include(":kernel")
