import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息从仓库根目录的 keystore.properties 读取（已被 .gitignore 排除）。
// 文件不存在时 release 产出未签名包，debug 构建不受影响。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.mikhailtail.handyagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mikhailtail.handyagent"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        // H5Assets 用 BuildConfig.VERSION_NAME 当解压标记：App 一升级就重铺前端产物，
        // 避免旧 asset 与新 index.html 混在一起。AGP 8 起不再默认生成这个类。
        buildConfig = true
    }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            // Ktor 及其传递依赖各自带了这些，不排除会在打包时报重复。
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
    testOptions { unitTests.isReturnDefaultValues = true }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile").orEmpty())
                storePassword = keystoreProps.getProperty("storePassword").orEmpty()
                keyAlias = keystoreProps.getProperty("keyAlias").orEmpty()
                keyPassword = keystoreProps.getProperty("keyPassword").orEmpty()
            }
        }
    }

    buildTypes {
        release {
            // 暂不开混淆：新架构尚未在真机验证，先求稳。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    // 本地 HTTP+WS 服务端。纯 JVM 模块，逻辑可在电脑上直接跑测试。
    implementation(project(":server"))
    // 无障碍服务实现 MobileCapability 契约（契约在 :kernel-api）。
    implementation(project(":kernel-api"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.core:core-ktx:1.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
