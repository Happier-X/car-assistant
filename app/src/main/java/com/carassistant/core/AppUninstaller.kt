package com.carassistant.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay

/**
 * 应用卸载器 —— 多策略降级链。
 *
 * ## 策略选择逻辑
 *
 * 卸载和启动不一样：它几乎总是**破坏性操作**，所以策略设计原则是
 * 「能确定结果的路径优先，不确定的路径放最后」。
 *
 * | 场景 | 策略 | 说明 |
 * |------|------|------|
 * | 普通应用 + root | U1 `pm uninstall` | 最干脆，无任何弹窗 |
 * | 普通应用 + 无 root | U2 系统卸载框 | 拉起系统确认页，用户手动点「卸载」 |
 * | 系统应用 + root | U3 `pm uninstall --user 0` | 只对当前用户移除，可用 `install-existing` 恢复 |
 * | 系统应用 + 无 root | 拒绝 | 无法卸载，直接告知 |
 *
 * ## 为什么系统应用不用 `pm uninstall`
 *
 * 对系统应用执行不带 `--user` 的 `pm uninstall`，不同 Android 版本行为
 * 不一致：有的返回 DELETE_FAILED_INTERNAL_ERROR，有的会连系统分区里的
 * APK 一起清掉导致无法恢复。`--user 0` 是官方支持的「为当前用户卸载」，
 * APK 仍留在 /system，随时可以恢复——这对车机尤其重要，删错了导航
 * 还有后悔药。
 */
object AppUninstaller {

    private const val TAG = "Uninstall"

    data class Outcome(
        val success: Boolean,
        val strategy: String,
        val trace: List<String>,
        /** 拉起了系统卸载框，最终结果取决于用户是否确认 */
        val needsUserConfirm: Boolean = false,
    )

    /**
     * 卸载指定应用。
     *
     * @param allowRoot 是否允许走 root 通道
     */
    suspend fun uninstall(
        context: Context,
        packageName: String,
        allowRoot: Boolean,
    ): Outcome {
        val trace = mutableListOf<String>()

        if (packageName.isBlank()) {
            return Outcome(false, "包名为空", trace)
        }
        if (packageName == context.packageName) {
            return Outcome(false, "不能卸载本应用", trace)
        }
        if (isProtectedPackage(packageName)) {
            RunLog.w(TAG, "拒绝卸载系统关键组件：$packageName")
            return Outcome(false, "系统关键组件，已禁止卸载", trace)
        }

        val isSystem = runCatching {
            Permissions.isSystemApp(context.packageManager.getApplicationInfo(packageName, 0))
        }.getOrDefault(false)
        val rootOk = allowRoot && ShellRunner.detectRoot() is ShellRunner.RootStatus.Available

        RunLog.step("开始卸载：$packageName（${if (isSystem) "系统应用" else "普通应用"}）")

        // ---- 系统应用：只能 root + --user 0 ----
        if (isSystem) {
            if (!rootOk) {
                trace += "系统应用需要 root 才能卸载"
                return Outcome(false, "系统应用需要 root", trace)
            }
            return uninstallSystemForUser(context, packageName, trace)
        }

        // ---- 普通应用：优先 root 直卸（结果确定），无 root 再走系统框 ----
        if (rootOk) {
            val result = ShellRunner.execRoot("pm uninstall $packageName", timeoutMs = 20_000)
            trace += "U1 root: pm uninstall -> ${result?.brief() ?: "null"}"
            if (result?.isSuccess == true && isGone(context, packageName)) {
                return Outcome(true, "root pm uninstall", trace)
            }

            // 极少数 ROM 上 pm uninstall 语义异常，补一发 --user 0
            val result2 = ShellRunner.execRoot(
                "pm uninstall --user 0 $packageName",
                timeoutMs = 20_000,
            )
            trace += "U1 root: pm uninstall --user 0 -> ${result2?.brief() ?: "null"}"
            if (result2?.isSuccess == true && isGone(context, packageName)) {
                return Outcome(true, "root pm uninstall --user 0", trace)
            }
        }

        // ---- 系统卸载框：无 root 时的唯一选择，有 root 时作为兜底 ----
        if (trySystemUninstaller(context, packageName, trace)) {
            val confirmed = awaitUninstalled(context, packageName, timeoutMs = 60_000)
            return if (confirmed) {
                Outcome(true, "系统卸载框（用户确认）", trace)
            } else {
                trace += "等待用户确认超时或已取消"
                Outcome(false, "未确认卸载", trace, needsUserConfirm = true)
            }
        }

        return Outcome(false, "所有卸载方式均失败", trace)
    }

    // ==================== 各级策略 ====================

    /** 系统应用：--user 0 卸载（保留 APK，可恢复） */
    private suspend fun uninstallSystemForUser(
        context: Context,
        packageName: String,
        trace: MutableList<String>,
    ): Outcome {
        val result = ShellRunner.execRoot(
            "pm uninstall --user 0 $packageName",
            timeoutMs = 20_000,
        )
        trace += "U3 root: pm uninstall --user 0 -> ${result?.brief() ?: "null"}"

        val gone = isGone(context, packageName)
        if (gone) {
            trace += "已从当前用户移除（APK 保留，可用 install-existing 恢复）"
            return Outcome(true, "root --user 0 卸载", trace)
        }
        return Outcome(false, "--user 0 卸载未生效", trace)
    }

    /**
     * 拉起系统卸载确认页。
     *
     * 用 ACTION_DELETE 而不是已废弃的 ACTION_UNINSTALL_PACKAGE：
     * 前者在所有 Android 版本上语义一致，且 targetSdk 28 下行为完全相同。
     */
    private fun trySystemUninstaller(
        context: Context,
        packageName: String,
        trace: MutableList<String>,
    ): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        trace += "U2 已拉起系统卸载确认页"
        true
    }.getOrElse {
        trace += "U2 拉起系统卸载页失败：${it.javaClass.simpleName}"
        false
    }

    /** 轮询等用户在系统框里点完确认。 */
    private suspend fun awaitUninstalled(
        context: Context,
        packageName: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(1500)
            if (isGone(context, packageName)) return true
        }
        return false
    }

    // ==================== 查询辅助 ====================

    /** 该包在当前用户下是否已不可见（=已卸载/已对用户移除） */
    private fun isGone(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
    }.isFailure

    /**
     * 系统关键组件保护名单。
     *
     * 车机上误删这些包会导致系统卡在开机动画，只能去 4S 店刷机。
     * 宁可少卸十个，不可错删一个。
     */
    fun isProtectedPackage(packageName: String): Boolean = packageName in setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.launcher",
        "com.android.launcher3",
        "com.android.launcher3.widgets",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.system",
        "com.android.incallui",
        "com.android.inputmethod", // 输入法框架
        "com.samsung.android.launcher",
    )
}
