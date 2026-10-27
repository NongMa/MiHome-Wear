// 顶层构建脚本：只负责声明插件版本，具体配置在 app/build.gradle.kts
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    // Kotlin 2.x 起 Compose 编译器独立成插件，版本与 Kotlin 保持一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}
