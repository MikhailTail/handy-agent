plugins {
    id("com.android.application") version "8.11.0" apply false
    id("com.android.library") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // 纯 JVM 模块（:server 以及后续的 :protocol / :kernel / :kernel-tools / :persistence）
    kotlin("jvm") version "1.9.22" apply false
}
