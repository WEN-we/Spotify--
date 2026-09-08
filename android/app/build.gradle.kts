// app：Android 入口模块（悬浮歌词）
// 规则：零第三方运行时依赖（仅 Android 标准 API + LRCLIB HTTP API）
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spotifytools.lyrics"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spotifytools.lyrics"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    // Release 用 debug 密钥签名（个人开源工具，便于直接分发；如需正式签名请替换）
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 核心模块（繁简转换 + LRC 解析）
    implementation(project(":core"))
    // 零第三方运行时依赖：HTTP 用 HttpURLConnection，JSON 用 org.json（系统内置）
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // 单元测试用真实 org.json（Android android.jar 中的是抛异常的桩；仅测试类路径，不影响运行时零依赖）
    testImplementation("org.json:json:20231013")
}
