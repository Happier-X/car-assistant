package com.carassistant.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限 / 能力检测的集中入口。
 *
 * 单独抽出来是因为这些检查散落各处很容易写法不一致（尤其是
 * 「未授予」和「系统不支持」这两种情况经常被混为一谈）。
 */
object Permissions {

    /**
     * 是否持有 WRITE_SECURE_SETTINGS。
     *
     * 这个权限是 signature|privileged 级别，普通安装拿不到，
     * 但可以通过 root 或 adb 手动授予，是「无 root 开 WiFi」的关键后路。
     */
    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED

    /** 是否已授予悬浮窗权限（Android 6+ 需要动态申请） */
    fun hasOverlay(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    /**
     * 是否已加入电池优化白名单。
     *
     * 车机上如果不加白名单，开机流程执行到一半可能被系统冻结，
     * 表现就是「WiFi 开了但亿连没起来」这种半成品状态。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    /** 获取所有已安装应用（含系统应用），供启动项选择器使用 */
    fun installedApps(packageManager: PackageManager): List<ApplicationInfo> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())

    fun isSystemApp(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    /** 当前设备可用内存，用于诊断页 */
    fun availableMemoryMb(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    }.getOrDefault(-1L)

    /** 跳转到系统的无障碍设置页 */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 跳转到悬浮窗权限页 */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 跳转到电池优化白名单申请页 */
    fun batterySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 跳转到本应用的详情页（用于授予其他权限） */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse {
            RunLog.e("Permissions", "跳转设置页失败", it)
            false
        }
}