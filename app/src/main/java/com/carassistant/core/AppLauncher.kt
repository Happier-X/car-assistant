package com.carassistant.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/**
 * 应用启动器 —— 多策略降级链。
 *
 * ## 为什么需要这么复杂
 *
 * Android 10 (API 29) 起引入「后台启动 Activity 限制」：
 * 后台进程调用 `startActivity()` 会被系统静默丢弃，logcat 里能看到
 * `Background activity start ... blocked`。而「开机自动拉起某个 App」
 * 恰好就是最典型的后台启动场景。
 *
 * 车机 ROM 通常没接入 GMS 的完整限制，但不同厂商差异极大：
 * 有的完全放行，有的需要 `SYSTEM_ALERT_WINDOW`，有的彻底封死。
 *
 * ## 策略链（成功即止，每级都复查前台包名）
 *
 * | 序 | 策略 | 前置条件 |
 * |----|------|---------|
 * | A1 | `startActivity` + NEW_TASK | 系统未拦截后台启动 |
 * | A2 | `su -c "am start -n pkg/cls"` | root |
 * | A3 | `su -c "am start --user 0 ..."` | root + 多用户 |
 * | A4 | `su -c "monkey -p pkg ..."` | root，最鲁棒 |
 * | A5 | 无障碍服务 `startActivity` | 用户开启无障碍 |
 * | A6 | 全屏 Intent 通知 | 以上全失败，改为提示用户 |
 *
 * 无障碍服务之所以是最强兜底：AccessibilityService 的 startActivity
 * **不受后台启动限制约束**，这是 Android 10+ 上无 root 拉起应用最可靠的途径。
 */
object AppLauncher {

    private const val TAG = "Launcher"

    data class Outcome(
        val success: Boolean,
        val strategy: LaunchStrategy,
        val trace: List<String>,
        /** 需要用户去开启无障碍服务 */
        val needsAccessibility: Boolean = false,
    ) {
        val summary: String
            get() = if (success) "已启动（${strategy.display}）" else "启动失败（${strategy.display}）"
    }

    /**
     * 启动目标应用。
     *
     * @param verify 是否复查前台。关闭后只要指令发出就算成功，
     *               适合那些启动慢（冷启动要十几秒）的车机。
     */
    suspend fun launch(
        context: Context,
        packageName: String,
        allowRoot: Boolean,
        verify: Boolean = true,
    ): Outcome {
        val trace = mutableListOf<String>()

        if (packageName.isBlank()) {
            return Outcome(false, LaunchStrategy.FAILED, trace + "包名为空")
        }

        // 已在前台就不重复拉起，避免「刚打开又被重启」的闪屏
        if (verify && isInForeground(context, packageName)) {
            RunLog.i(TAG, "$packageName 已在前台，跳过")
            return Outcome(true, LaunchStrategy.ALREADY_FOREGROUND, trace + "已在前台")
        }

        if (tryDirect(context, packageName, verify, trace)) {
            return Outcome(true, LaunchStrategy.DIRECT, trace)
        }

        if (allowRoot && ShellRunner.detectRoot() is ShellRunner.RootStatus.Available) {
            if (tryRootAm(context, packageName, verify, trace, withUser = false)) {
                return Outcome(true, LaunchStrategy.ROOT_AM, trace)
            }
            if (tryRootAm(context, packageName, verify, trace, withUser = true)) {
                return Outcome(true, LaunchStrategy.ROOT_AM_USER, trace)
            }
            if (tryRootMonkey(context, packageName, verify, trace)) {
                return Outcome(true, LaunchStrategy.ROOT_MONKEY, trace)
            }
        } else if (allowRoot) {
            trace += "A2/A3/A4 root: 无 root，跳过"
        }

        if (tryAccessibility(context, packageName, verify, trace)) {
            return Outcome(true, LaunchStrategy.ACCESSIBILITY, trace)
        }

        // 最后兜底：发全屏通知，至少能让用户一键打开
        val notified = postFullScreenNotification(context, packageName, trace)
        return Outcome(
            success = false,
            strategy = if (notified) LaunchStrategy.FSI_NOTIFICATION else LaunchStrategy.FAILED,
            trace = trace,
            needsAccessibility = !AccessibilityBridge.isServiceReady(),
        )
    }

    // ==================== 各级策略 ====================

    /** A1: 标准直连 */
    private suspend fun tryDirect(
        context: Context,
        packageName: String,
        verify: Boolean,
        trace: MutableList<String>,
    ): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            trace += "A1 直连: 该包无 launcher intent"
            return false
        }
        return runCatching {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            context.startActivity(launchIntent)
            trace += "A1 直连: startActivity 已发出"
            if (!verify) return true
            delay(1600)
            val ok = isInForeground(context, packageName)
            trace += "A1 直连: 前台复查 ${if (ok) "通过" else "未通过"}"
            ok
        }.getOrElse {
            trace += "A1 直连: 异常 ${it.javaClass.simpleName}"
            false
        }
    }

    /** A2/A3: am start */
    private suspend fun tryRootAm(
        context: Context,
        packageName: String,
        verify: Boolean,
        trace: MutableList<String>,
        withUser: Boolean,
    ): Boolean {
        val component = resolveLauncherActivity(context, packageName)
        val userFlag = if (withUser) "--user 0 " else ""
        val label = if (withUser) "A3" else "A2"

        val commands = buildList {
            if (component != null) {
                add("am start $userFlag-n ${component.flattenToShortString()}")
            }
            add("am start $userFlag-a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName")
        }

        for (command in commands) {
            val result = ShellRunner.execRoot(command, timeoutMs = 6000) ?: return false
            trace += "$label root: $command -> ${result.brief()}"

            if (!verify) return result.isSuccess
            delay(1700)
            if (isInForeground(context, packageName)) {
                trace += "$label root: 前台复查通过"
                return true
            }
        }
        return false
    }

    /** A4: monkey —— 不依赖 Activity 名，能绕过部分 ROM 的组件解析问题 */
    private suspend fun tryRootMonkey(
        context: Context,
        packageName: String,
        verify: Boolean,
        trace: MutableList<String>,
    ): Boolean {
        val result = ShellRunner.execRoot(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
            timeoutMs = 8000,
        )
        trace += "A4 root monkey -> ${result?.brief() ?: "null"}"
        if (!verify) return result?.isSuccess == true
        delay(2000)
        val ok = isInForeground(context, packageName)
        trace += "A4 root monkey: 前台复查 ${if (ok) "通过" else "未通过"}"
        return ok
    }

    /** A5: 无障碍代启动 —— 不受后台启动限制 */
    private suspend fun tryAccessibility(
        context: Context,
        packageName: String,
        verify: Boolean,
        trace: MutableList<String>,
    ): Boolean {
        if (!AccessibilityBridge.isServiceReady()) {
            trace += "A5 无障碍: 服务未开启"
            return false
        }
        val sent = AccessibilityBridge.launchPackage(packageName)
        if (!sent) {
            trace += "A5 无障碍: 指令发送失败"
            return false
        }
        trace += "A5 无障碍: 指令已发送"
        if (!verify) return true
        delay(1800)
        val ok = isInForeground(context, packageName)
        trace += "A5 无障碍: 前台复查 ${if (ok) "通过" else "未通过"}"
        return ok
    }

    /** A6: 全屏 Intent 通知，系统会在合适时机弹出 */
    private fun postFullScreenNotification(
        context: Context,
        packageName: String,
        trace: MutableList<String>,
    ): Boolean = runCatching {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName) ?: return@runCatching false

        Notifications.postLaunchFallback(context, packageName, labelOf(context, packageName), launchIntent)
        trace += "A6 已发出全屏通知兜底"
        true
    }.getOrElse {
        trace += "A6 通知失败: ${it.javaClass.simpleName}"
        false
    }

    // ==================== 包信息查询 ====================

    /** 解析包的主入口 Activity，按 LAUNCHER → LEANBACK → 兜底 依次尝试 */
    fun resolveLauncherActivity(context: Context, packageName: String): ComponentName? {
        val pm = context.packageManager

        runCatching {
            pm.getLaunchIntentForPackage(packageName)?.component?.let { return it }
        }

        // 车机应用常注册在 LEANBACK（车载桌面）分类下，标准查询会漏掉
        val categories = listOf(
            Intent.CATEGORY_LAUNCHER,
            Intent.CATEGORY_LEANBACK_LAUNCHER,
            CATEGORY_CAR_LAUNCHER,
        )
        for (category in categories) {
            runCatching {
                val probe = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(category)
                    setPackage(packageName)
                }
                pm.queryIntentActivities(probe, 0).firstOrNull()?.activityInfo?.let {
                    return ComponentName(it.packageName, it.name)
                }
            }
        }
        return null
    }

    /** 该包是否可被启动（有入口） */
    fun isLaunchable(context: Context, packageName: String): Boolean =
        resolveLauncherActivity(context, packageName) != null

    fun labelOf(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * 查询当前前台包名。
     *
     * 三路并用，因为单一路径在不同 ROM 上都可能返回空：
     *  1. root 下 `dumpsys activity activities`（最准）
     *  2. 无障碍服务缓存（onAccessibilityEvent 里记录的，无 root 时可用）
     *  3. 空字符串表示「未知」——调用方需把「未知」当作「不阻塞流程」处理
     */
    suspend fun foregroundPackage(context: Context): String {
        ShellRunner.foregroundPackageViaRoot()?.let { return it }
        AccessibilityBridge.currentPackage()?.let { if (it.isNotBlank()) return it }
        return ""
    }

    /**
     * 是否处于前台。
     *
     * 注意：检测手段不可用时返回 **true**（乐观判断）。这是刻意的取舍 ——
     * 车机上探测失败很常见，如果这时返回 false，会触发所有降级策略白跑一遍，
     * 甚至把一个已经正常打开的 App 再重启一次。宁可「漏报已启动」，
     * 也不要「误判未启动」。
     */
    suspend fun isInForeground(context: Context, packageName: String): Boolean {
        val foreground = foregroundPackage(context)
        if (foreground.isBlank()) return true
        return foreground == packageName
    }

    /** 列出所有可启动的应用 */
    fun listLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val seen = mutableSetOf<String>()

        val entries = buildList {
            // 先收有入口的（三类 category 全覆盖）
            listOf(
                Intent.CATEGORY_LAUNCHER,
                Intent.CATEGORY_LEANBACK_LAUNCHER,
                CATEGORY_CAR_LAUNCHER,
            ).forEach { category ->
                runCatching {
                    val probe = Intent(Intent.ACTION_MAIN).addCategory(category)
                    pm.queryIntentActivities(probe, 0).forEach { info ->
                        val pkg = info.activityInfo?.packageName ?: return@forEach
                        if (pkg == context.packageName || !seen.add(pkg)) return@forEach
                        add(
                            AppEntry(
                                packageName = pkg,
                                label = info.loadLabel(pm).toString(),
                                isSystem = Permissions.isSystemApp(
                                    runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                                        ?: return@forEach
                                ),
                                hasLauncher = true,
                            )
                        )
                    }
                }
            }

            // 再补上所有已安装应用。车机上的亿连/CarLife 之类常常
            // 没有标准 launcher 入口，必须靠这一轮才能被发现
            Permissions.installedApps(pm).forEach { info ->
                val pkg = info.packageName
                if (pkg == context.packageName || !seen.add(pkg)) return@forEach
                add(
                    AppEntry(
                        packageName = pkg,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(pkg),
                        isSystem = Permissions.isSystemApp(info),
                        hasLauncher = resolveLauncherActivity(context, pkg) != null,
                    )
                )
            }
        }

        // 有入口的排前面，然后用户应用优先，最后按名称排序
        return entries.sortedWith(
            compareByDescending<AppEntry> { it.hasLauncher }
                .thenBy { it.isSystem }
                .thenBy { it.label.lowercase() }
        )
    }

    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val hasLauncher: Boolean,
    )

    const val CATEGORY_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"
}