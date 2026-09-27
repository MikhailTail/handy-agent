plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 消息体直接以 JsonElement 在层间传递：转成自定义模型只会引入失真，
    // 而这一层要保证"透传"的保真度（见 project/view 里对 content 的处理）。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // LlmClient 以 Flow 暴露流式事件：调用方（内核、server）都需要它，
    // 所以是 api 而不是 implementation。
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
