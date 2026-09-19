package com.carassistant.core

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.delay

/**
 * WiFi 控制器 —— 多策略降级链，这是整个项目的技术核心。
 *
 * ## 为什么需要这么复杂
 *
 * Android 对「App 打开 WiFi」的限制是逐版本收紧的：
 *  - Android 9 (API 28)：`setWifiEnabled()` 仍可用，但开始有 deprecate 警告
 *  - Android 10 (API 29)：**targetSdk >= 29 时 `setWifiEnabled()` 直接返回 false**
 *  - Android 10+：引入「软开关」，Settings 里的开关未必等于射频真实状态
 *  - Android 12+：部分车机 ROM 将 WifiManager 的 set 类接口整体封死
 *
 * 车机（尤其合资品牌后装/前装）通常基于 Android 9~12，且**不走 Google Play**，
 * 所以我们可以合法地使用 `targetSdk = 28` 这个「后门」——这是无 root 场景下
 * 唯一能真正静默打开 WiFi 的途径。
 *
 * ## 策略链（按侵入性从低到高，成功即止）
 *
 * | 序 | 策略 | 前置条件 |
 * |----|------|---------|
 * | S1 | `setWifiEnabled(true)` | targetSdk < 29（本项目已保证）|
 * | S2 | 反射隐藏接口 | 部分 ROM 需要 |
 * | S3 | `su -c "svc wifi enable"` | root |
 * | S4 | `su -c "cmd wifi set-wifi-enabled enabled"` | root |
 * | S5 | `Settings.Global.WIFI_ON = 1` | WRITE_SECURE_SETTINGS |
 * | S6 | 无障碍服务代点设置页开关 | 用户开启无障碍 |
 *
 * **每一级执行后都会真实复查 `isWifiEnabled()`**，绝不盲目往下走，
 * 也绝不「报成功但其实没开」——这是车机自动化最容易被坑的地方。
 */
object WifiController {

    private const val TAG = "Wifi"

    data class Outcome(
        val success: Boolean,
        val strategy: WifiStrategy,
        val trace: List<String>,
        /** 需要无障碍服务拉起设置页并代点开关 */
        val needsUiFallback: Boolean = false,
    ) {
        val summary: String
            get() = if (success) "WiFi 已开启（${strategy.display}）" else "WiFi 开启失败"
    }

    fun wifiManager(context: Context): WifiManager? =
        runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()

    /** 真实读取（非缓存）WiFi 开关状态 */
    fun isEnabled(context: Context): Boolean =
        runCatching { wifiManager(context)?.isWifiEnabled == true }.getOrDefault(false)

    /**
     * 确保 WiFi 处于开启状态。
     *
     * 静默手段全部失败且 [allowUiFallback] 为真时，会**自动执行 UI 兜底**：
     * 拉起系统设置页，让无障碍服务代点开关，然后轮询确认。
     * 这样调用方（编排器 / UI 的手动测试）无需关心兜底细节，
     * 也避免了两处各写一份兜底逻辑导致行为不一致。
     *
     * @param allowRoot 是否允许走 root 通道
     * @param allowUiFallback 静默方式全失败时，是否允许 UI 兜底
     * @param timeoutMs 每一级的复查等待上限
     */
    suspend fun ensureEnabled(
        context: Context,
        allowRoot: Boolean,
        allowUiFallback: Boolean,
        timeoutMs: Long = 2500,
        uiFallbackTimeoutMs: Long = 15_000,
    ): Outcome {
        val trace = mutableListOf<String>()

        if (isEnabled(context)) {
            RunLog.i(TAG, "WiFi 本就已开启，无需操作")
            return Outcome(true, WifiStrategy.ALREADY_ON, trace)
        }

        // S1: 标准 API
        if (tryDirectApi(context, trace, timeoutMs)) {
            return Outcome(true, WifiStrategy.API_DIRECT, trace)
        }

        // S2: 反射
        if (tryReflection(context, trace, timeoutMs)) {
            return Outcome(true, WifiStrategy.API_REFLECT, trace)
        }

        // S3/S4: root
        if (allowRoot) {
            if (ShellRunner.detectRoot() is ShellRunner.RootStatus.Available) {
                if (tryRootSvc(context, trace, timeoutMs)) {
                    return Outcome(true, WifiStrategy.ROOT_SVC, trace)
                }
                if (tryRootCmdWifi(context, trace, timeoutMs)) {
                    return Outcome(true, WifiStrategy.ROOT_CMD, trace)
                }
            } else {
                trace += "S3/S4 root: 无 root，跳过"
            }
        }

        // S5: 安全设置
        if (trySecureSettings(context, trace, timeoutMs)) {
            return Outcome(true, WifiStrategy.SECURE_SETTINGS, trace)
        }

        // S6: UI 兜底
        if (!allowUiFallback) {
            return Outcome(false, WifiStrategy.FAILED, trace)
        }
        return runUiFallback(context, trace, uiFallbackTimeoutMs)
    }

    /**
     * S6：拉起设置页 + 无障碍代点开关。
     *
     * 这是最后一招，所以每一步失败原因都要写进 trace ——
     * 用户看到「失败」时必须知道该去开哪个权限。
     */
    private suspend fun runUiFallback(
        context: Context,
        trace: MutableList<String>,
        uiFallbackTimeoutMs: Long,
    ): Outcome {
        if (!AccessibilityBridge.isServiceReady()) {
            trace += "S6 UI 兜底: 无障碍服务未开启，无法代点（请在设置中开启）"
            RunLog.w(TAG, "需要 UI 兜底但无障碍未开启")
            return Outcome(false, WifiStrategy.FAILED, trace, needsUiFallback = true)
        }

        trace += "S6 UI 兜底: 拉起设置页并由无障碍代点开关"
        RunLog.step("静默开 WiFi 全部失败，改用无障碍代点设置页开关")

        if (!openWifiSettings(context)) {
            trace += "S6 UI 兜底: 打开设置页失败"
            return Outcome(false, WifiStrategy.FAILED, trace, needsUiFallback = true)
        }

        delay(2500) // 等设置页渲染完成

        if (!AccessibilityBridge.requestWifiToggle(uiFallbackTimeoutMs)) {
            trace += "S6 UI 兜底: 无障碍未受理点击任务"
            return Outcome(false, WifiStrategy.FAILED, trace, needsUiFallback = true)
        }

        // 轮询确认点击是否生效
        val deadline = System.currentTimeMillis() + uiFallbackTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(800)
            if (isEnabled(context)) {
                trace += "S6 UI 兜底: 复查确认已开启"
                return Outcome(true, WifiStrategy.UI_FALLBACK, trace)
            }
        }

        AccessibilityBridge.cancelWifiToggle()
        trace += "S6 UI 兜底: 超时，开关未被成功点击"
        return Outcome(false, WifiStrategy.FAILED, trace, needsUiFallback = true)
    }

    // ==================== 各级策略 ====================

    /** S1: 标准 API。targetSdk=28 时在 Android 10+ 上依然有效。 */
    private suspend fun tryDirectApi(
        context: Context,
        trace: MutableList<String>,
        timeoutMs: Long,
    ): Boolean {
        val manager = wifiManager(context)
        if (manager == null) {
            trace += "S1 标准API: WifiManager 不可用"
            return false
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val accepted = manager.setWifiEnabled(true)
            trace += "S1 标准API: setWifiEnabled(true) 返回 $accepted"
            awaitEnabled(context, timeoutMs, trace, "S1")
        }.getOrElse {
            trace += "S1 标准API: 异常 ${it.javaClass.simpleName}"
            false
        }
    }

    /** S2: 反射调用隐藏接口，绕过部分 ROM 的重载封装。 */
    private suspend fun tryReflection(
        context: Context,
        trace: MutableList<String>,
        timeoutMs: Long,
    ): Boolean {
        val manager = wifiManager(context) ?: return false

        data class Attempt(val name: String, val paramTypes: Array<Class<*>>, val args: Array<Any?>)

        val attempts = buildList {
            add(Attempt("setWifiEnabled", arrayOf(Boolean::class.java), arrayOf<Any?>(true)))
            // 某些 ROM 有带 persist 参数的重载
            add(
                Attempt(
                    "setWifiEnabledPersist",
                    arrayOf(Boolean::class.java, Boolean::class.java),
                    arrayOf<Any?>(true, true),
                )
            )
        }

        for (attempt in attempts) {
            val ok = runCatching {
                val method = WifiManager::class.java
                    .getDeclaredMethod(attempt.name, *attempt.paramTypes)
                    .apply { isAccessible = true }
                method.invoke(manager, *attempt.args)
            }.onFailure {
                trace += "S2 反射: ${attempt.name} 不可用(${it.javaClass.simpleName})"
            }.isSuccess

            if (!ok) continue

            trace += "S2 反射: ${attempt.name} 调用成功"
            if (awaitEnabled(context, timeoutMs, trace, "S2")) return true
        }
        return false
    }

    /** S3: svc wifi enable —— 最经典的 root 方式 */
    private suspend fun tryRootSvc(
        context: Context,
        trace: MutableList<String>,
        timeoutMs: Long,
    ): Boolean {
        val result = ShellRunner.execRoot("svc wifi enable", timeoutMs = 5000)
        trace += "S3 root: svc wifi enable -> ${result?.brief() ?: "null"}"
        return awaitEnabled(context, timeoutMs, trace, "S3")
    }

    /** S4: cmd wifi —— Android 10+ 的新命令 */
    private suspend fun tryRootCmdWifi(
        context: Context,
        trace: MutableList<String>,
        timeoutMs: Long,
    ): Boolean {
        val commands = listOf(
            "cmd wifi set-wifi-enabled enabled",
            "cmd -w wifi set-wifi-enabled enabled",
        )
        for (command in commands) {
            val result = ShellRunner.execRoot(command, timeoutMs = 5000)
            trace += "S4 root: $command -> ${result?.brief() ?: "null"}"
            if (awaitEnabled(context, timeoutMs, trace, "S4")) return true
        }
        return false
    }

    /**
     * S5: 写安全设置 wifi_on。
     * 需要 WRITE_SECURE_SETTINGS，可通过 root 或
     * `adb shell pm grant com.carassistant android.permission.WRITE_SECURE_SETTINGS` 获得。
     */
    private suspend fun trySecureSettings(
        context: Context,
        trace: MutableList<String>,
        timeoutMs: Long,
    ): Boolean {
        val wrote = runCatching {
            Settings.Global.putInt(context.contentResolver, Settings.Global.WIFI_ON, 1)
        }.getOrElse {
            trace += "S5 安全设置: 无权限写入(${it.javaClass.simpleName})"
            false
        }

        if (!wrote) return false
        trace += "S5 安全设置: WIFI_ON=1 已写入"

        if (awaitEnabled(context, timeoutMs, trace, "S5")) return true

        // 写设置只改了状态位，还要再触发一次实际切换
        runCatching {
            @Suppress("DEPRECATION")
            wifiManager(context)?.setWifiEnabled(true)
        }
        trace += "S5 安全设置: 补发 setWifiEnabled 触发实际切换"
        return awaitEnabled(context, timeoutMs, trace, "S5-补")
    }

    /** 轮询复查，确认真的开了才算成功 */
    private suspend fun awaitEnabled(
        context: Context,
        timeoutMs: Long,
        trace: MutableList<String>,
        stage: String,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isEnabled(context)) {
                trace += "$stage 复查确认已开启"
                return true
            }
            delay(250)
        }
        trace += "$stage 复查仍未开启"
        return false
    }

    // ==================== UI 兜底支持 ====================

    /** 拉起系统 WiFi 设置页，交给无障碍服务去点开关 */
    fun openWifiSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        RunLog.i(TAG, "已拉起 WiFi 设置页，等待无障碍服务代点开关")
        true
    }.getOrElse {
        RunLog.e(TAG, "拉起 WiFi 设置页失败", it)
        false
    }

    /** 无障碍点击完成后调用，做最终确认 */
    fun verifyAfterUiClick(context: Context): Boolean = isEnabled(context)

    // ==================== 自检 ====================

    suspend fun snapshot(context: Context): CapabilitySnapshot {
        val root = ShellRunner.detectRoot()
        val appInfo = context.applicationInfo

        return CapabilitySnapshot(
            model = "${Build.BRAND} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT,
            appTargetSdk = appInfo.targetSdkVersion,
            hasRoot = root is ShellRunner.RootStatus.Available,
            rootDetail = when (root) {
                is ShellRunner.RootStatus.Available -> "可用：${root.suPrefix.joinToString(" ")}"
                is ShellRunner.RootStatus.Unavailable -> root.reason
                ShellRunner.RootStatus.Unknown -> "未探测"
            },
            wifiEnabled = isEnabled(context),
            hasWriteSecureSettings = Permissions.hasWriteSecureSettings(context),
            accessibilityEnabled = AccessibilityBridge.isServiceReady(),
            overlayGranted = Permissions.hasOverlay(context),
            batteryOptimizationIgnored = Permissions.isIgnoringBatteryOptimizations(context),
        )
    }
}