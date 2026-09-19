package com.carassistant.core

import kotlinx.serialization.Serializable

/**
 * 一个「开机启动项」。
 *
 * @param packageName 目标应用包名
 * @param label       显示名（冗余存储，避免每次读盘都要查 PackageManager）
 * @param waitBeforeMs 拉起本项之前额外等待的毫秒数，用于错开多应用的启动风暴
 * @param enabled     是否参与执行
 */
@Serializable
data class LaunchTarget(
    val packageName: String,
    val label: String = "",
    val waitBeforeMs: Int = 3000,
    val enabled: Boolean = true,
)

/** 单次启动流程的结果报告 */
data class BootReport(
    val trigger: String,
    val wifiRequested: Boolean = false,
    val wifiOk: Boolean = false,
    val wifiSummary: String = "",
    val launchResults: List<LaunchResult> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = System.currentTimeMillis(),
) {
    val launchOk: Int get() = launchResults.count { it.success }
    val launchFail: Int get() = launchResults.count { !it.success }
    val durationMs: Long get() = endedAt - startedAt

    fun toDisplayText(): String = buildString {
        appendLine("触发方式：$trigger")
        appendLine("耗时：${durationMs} ms")
        if (wifiRequested) {
            appendLine("WiFi：${if (wifiOk) "✔" else "✘"} $wifiSummary")
        } else {
            appendLine("WiFi：已跳过")
        }
        if (launchResults.isEmpty()) {
            appendLine("启动项：无")
        } else {
            appendLine("启动项：")
            launchResults.forEach { appendLine("  · ${it.label} ${if (it.success) "✔" else "✘"} ${it.detail}") }
        }
        append("结果：成功 $launchOk / 失败 $launchFail")
    }
}

data class LaunchResult(
    val packageName: String,
    val label: String,
    val success: Boolean,
    val strategy: String,
    val detail: String,
)

/** WiFi 开启所用的策略（按侵入性从低到高） */
enum class WifiStrategy(val display: String) {
    ALREADY_ON("本就已开启"),
    API_DIRECT("标准 API"),
    API_REFLECT("反射隐藏接口"),
    ROOT_SVC("root: svc wifi enable"),
    ROOT_CMD("root: cmd wifi"),
    SECURE_SETTINGS("安全设置 wifi_on"),
    UI_FALLBACK("无障碍代点设置页"),
    FAILED("全部失败"),
}

/** 拉起应用所用的策略 */
enum class LaunchStrategy(val display: String) {
    ALREADY_FOREGROUND("已在前台"),
    DIRECT("标准直连"),
    ROOT_AM("root: am start"),
    ROOT_AM_USER("root: am start --user 0"),
    ROOT_MONKEY("root: monkey"),
    ACCESSIBILITY("无障碍代启动"),
    FSI_NOTIFICATION("全屏通知兜底"),
    FAILED("全部失败"),
    SKIPPED("已跳过"),
}

/** 流程中的一条进度事件，供 UI 实时展示 */
sealed interface FlowEvent {
    data class Step(val text: String) : FlowEvent
    data class Finished(val report: BootReport) : FlowEvent
}

/**
 * WiFi 开关的探测结果。
 *
 * 之所以要单独建模，是因为车机 ROM 的差异导致「能不能开 WiFi」这件事
 * 必须现场探明，不能靠假设。诊断页会把这里的信息原样展示给用户，
 * 让用户知道该去开哪个权限。
 */
data class CapabilitySnapshot(
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val fingerprint: String,
    val appTargetSdk: Int,
    val hasRoot: Boolean,
    val rootDetail: String,
    val wifiEnabled: Boolean,
    val hasWriteSecureSettings: Boolean,
    val accessibilityEnabled: Boolean,
    val overlayGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
) {
    /** 无 root 的情况下，WiFi 静默开关能否成功的预判 */
    val wifiSilentLikelyWorks: Boolean
        get() = hasRoot || appTargetSdk < 29 || hasWriteSecureSettings
}