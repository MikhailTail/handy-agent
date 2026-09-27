plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 转录是 JSONL，每行一个独立对象 —— 必须用宽松解析：字段会随 cc-haha 版本增减，
    // 遇到不认识的键要忽略而不是抛错（ignoreUnknownKeys），否则一次上游升级就全线读不动。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
