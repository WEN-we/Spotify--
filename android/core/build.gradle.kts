// core：纯 Kotlin JVM 模块——繁简转换 + LRC 解析（与 UI 完全解耦，可独立单测）
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        // 产出 17 字节码，与 app 模块（Android）、Java 编译目标一致
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 零第三方依赖：词表为内置资源数据
dependencies {
    testImplementation(kotlin("test"))
}
