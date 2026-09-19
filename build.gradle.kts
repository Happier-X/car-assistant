// 顶层构建脚本：只声明插件版本。
//
// 注意 AGP 9.x 起有了内置 Kotlin 支持（android.builtInKotlin 默认 true），
// 因此**不再需要** org.jetbrains.kotlin.android 插件 —— 重复应用会直接报错。
// 但 Compose 编译器插件和 serialization 插件仍然需要显式声明。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}