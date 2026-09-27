plugins {
    kotlin("jvm")
    // 只为了提供一个本地调试入口（见 DevServer.kt），让服务端能脱离设备单独跑起来。
    application
}

application {
    mainClass.set("dev.mikhailtail.handyagent.server.DevServerKt")
}

// 请求日志落到构建目录（绝对路径），供"前端零改动"的实测验证用：
// 让真实前端跑一遍，把它实际请求的路径记下来，比人肉 grep 前端代码可靠。
tasks.named<JavaExec>("run") {
    systemProperty(
        "handy.reqlog",
        layout.buildDirectory.file("requests.log").get().asFile.absolutePath,
    )
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":persistence"))
    implementation(project(":kernel"))
    implementation(project(":kernel-tools"))

    // Ktor 2.3.x 是 Kotlin 1.9 对应的稳定线。
    // CIO 引擎是纯 Kotlin 实现，Android 上可以直接跑，不需要 Netty/Java EE。
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    // 响应体里要原样透传转录里的 JsonElement，手拼字符串既易错又会破坏转义。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
