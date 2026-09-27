import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息从仓库根目录的 keystore.properties 读取（该文件已被 .gitignore 排除）。
// 文件不存在时 release 产出未签名包，debug 构建不受任何影响。
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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
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
            // 0.1.0 暂不开混淆：release 构建路径尚未在真机验证，先求稳。
            // 后续确认稳定后可改为 true，包体会进一步减小。
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
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
