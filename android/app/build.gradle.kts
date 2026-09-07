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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}
