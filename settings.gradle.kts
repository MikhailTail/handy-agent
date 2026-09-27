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
include(":app")
