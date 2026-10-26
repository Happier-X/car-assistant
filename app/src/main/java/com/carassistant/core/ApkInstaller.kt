package com.carassistant.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用安装器 —— 多策略降级链。
 *
 * ## 策略选择逻辑
 *
 * 卸载（[AppUninstaller]）几乎总是破坏性操作，所以那边是「结果确定的路径优先」；
 * 安装是用户想要的结果，所以这里按 **成功率 × 侵入性** 排序，逐级尝试，
 * **每级执行后都用 PackageManager 真实复查（包是否存在、版本是否到位）**，
 * 成功即停，失败如实记录 trace。
 *
 * | 序 | 策略 | 前置条件 | 说明 |
 * |----|------|---------|------|
 * | I1 | root: pm install -r -t | root | 静默覆盖安装；版本降级时自动补 -d |
 * | I2 | root: pm install --user 0 -r -t | root | 多用户 / 分身场景 |
 * | I3 | FileProvider + 系统安装器 | 允许安装未知来源 | 无 root 时的主力路径，需用户点「安装」 |
 * | I4 | 无障碍服务代点安装确认 | 无障碍服务已开启 | 车机上安装器按钮不好点时的兜底 |
 *
 * ## 为什么结果要分三态
 *
 * 无 root 的 Android 8+ 上，每个包都要用户在系统安装器里点一次「安装」。
 * 此时「我们成功调起了安装器」和「包真的装上了」是两件完全不同的事。
 * 如果混为一谈，界面就会显示一个漂亮的绿色「安装成功」，而车上其实什么都没装 ——
 * 这是本项目最不能接受的失败模式。所以结果用 [InstallState] 明确区分：
 * 真装上了 / 只调起了安装器等待用户确认 / 彻底失败。
 *
 * ## 为什么 root 路径要先暂存到 /data/local/tmp
 *
 * pm install 是让 system_server（system 用户）去打开这个 APK 文件。
 * App 私有目录（/data/data/... 与应用专属外部目录）system 用户读不到，
 * 会报 INSTALL_FAILED_INVALID_URI 之类。所以先 cp 到 /data/local/tmp
 * 并 chmod 644，装完再清理。
 */
object ApkInstaller {

    private const val TAG = "Install"

    /** FileProvider 的 authority 后缀，必须与 manifest 声明一致 */
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** 等用户在系统安装器里点「安装」的最长时间 */
    private const val USER_CONFIRM_TIMEOUT_MS = 90_000L

    /** 无障碍代点安装确认的窗口期 */
    private const val ACCESSIBILITY_TIMEOUT_MS = 120_000L

    /** 从 APK 文件里解析出来的元信息（此时包还没进系统） */
    data class ApkMeta(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val label: String,
    )

    /**
     * 安装结果。
     *
     * @param state 三态语义，见 [InstallState]
     * @param strategy 最后停在哪一级策略（或最终失败项）
     * @param trace 每一级的真实返回值与复查结论
     * @param needsUnknownSources 需要用户先允许本应用「安装未知应用」
     * @param needsAccessibility 无障碍服务没开，I4 兜底不可用
     */
    data class Outcome(
        val state: InstallState,
        val strategy: InstallStrategy,
        val trace: List<String>,
        val meta: ApkMeta? = null,
        val needsUnknownSources: Boolean = false,
        val needsAccessibility: Boolean = false,
    ) {
        /** 只有复查确认装上了才算成功 —— 不允许乐观 */
        val success: Boolean get() = state == InstallState.INSTALLED
    }

    /**
     * 安装指定 APK 文件。
     *
     * @param allowRoot 是否允许走 root 静默通道（沿用设置页的「允许使用 root」开关）
     * @param allowAccessibilityFallback 是否允许 I4 无障碍代点兜底。默认关闭：
     * 安装器的公开窗口信息不能可靠证明当前 APK 就是已确认的目标。
     */
    suspend fun install(
        context: Context,
        apkPath: String,
        allowRoot: Boolean,
        allowAccessibilityFallback: Boolean = false,
    ): Outcome {
        val trace = mutableListOf<String>()

        val apk = File(apkPath)
        if (!apk.exists() || !apk.isFile) {
            trace += "文件不存在：$apkPath"
            RunLog.fail("安装失败：文件不存在 $apkPath")
            return Outcome(InstallState.FAILED, InstallStrategy.FAILED, trace)
        }
        if (apk.length() <= 0L) {
            trace += "文件长度为 0：$apkPath"
            RunLog.fail("安装失败：文件为空")
            return Outcome(InstallState.FAILED, InstallStrategy.FAILED, trace)
        }

        val meta = readMeta(context, apk)
        if (meta == null) {
            trace += "解析失败：文件损坏、不是 APK，或最低系统版本高于本机"
            RunLog.fail("安装失败：无法解析 $apkPath")
            return Outcome(InstallState.FAILED, InstallStrategy.INVALID_APK, trace)
        }

        val updatedBefore = installedUpdateTime(context, meta.packageName)
        val installedBefore = installedVersionCode(context, meta.packageName)
        if (installedBefore != null) {
            trace += "本机已安装 ${meta.packageName} v$installedBefore，" +
                if (meta.versionCode < installedBefore) {
                    "本次目标 v" + meta.versionCode + " 是降级安装"
                } else {
                    "本次为覆盖安装"
                }
        }

        RunLog.step(
            "开始安装：" + meta.label + " " + meta.versionName +
                " (v" + meta.versionCode + ") ← " + apk.name
        )

        val rootOk = allowRoot && ShellRunner.detectRoot() is ShellRunner.RootStatus.Available
        if (allowRoot && !rootOk) {
            trace += "root 通道不可用：未授权或设备未 root"
        }

        // ---------- I1 / I2：root 静默安装 ----------
        if (rootOk) {
            val tmpPath = stageForRootInstall(apk, trace)
            if (tmpPath != null) {
                rootPmInstall(context, meta, tmpPath, trace, updatedBefore, withUser = false)?.let {
                    cleanupRootTemp(tmpPath, trace)
                    return it
                }
                rootPmInstall(context, meta, tmpPath, trace, updatedBefore, withUser = true)?.let {
                    cleanupRootTemp(tmpPath, trace)
                    return it
                }
                cleanupRootTemp(tmpPath, trace)
            } else {
                trace += "I1/I2 跳过：APK 暂存到 /data/local/tmp 失败"
            }
        }

        // ---------- I3：系统安装器 ----------
        // 「安装未知应用」在 Android 8+ 上是按应用授予的，默认关闭。这里只做前置提示、
        // 不做拦路虎：个别 ROM 的 canRequestPackageInstalls() 不准，直接拒绝反而会误伤，
        // 所以照样去调安装器，真调不起来再引导用户去授权页。
        val unknownSourcesOk = Permissions.canInstallPackages(context)
        if (!unknownSourcesOk) {
            trace += "I3 前置检查：未检测到「安装未知应用」授权（Android 8+ 按应用授予），仍尝试调起安装器"
        }

        var installerStarted = startSystemInstaller(context, apk, trace)
        if (installerStarted) {
            trace += "等待用户确认（最长 " + (USER_CONFIRM_TIMEOUT_MS / 1000) + " 秒）…"
            if (awaitInstalled(context, meta, USER_CONFIRM_TIMEOUT_MS, trace, updatedBefore)) {
                trace += "复查通过：包版本与目标一致，且安装更新时间已变化"
                return Outcome(InstallState.INSTALLED, InstallStrategy.SYSTEM_INSTALLER, trace, meta)
            }
            trace += "等待超时：安装器已调起，但用户没有完成确认"
        }

        // ---------- I4：无障碍代点安装确认 ----------
        val a11yReady = AccessibilityBridge.isServiceReady()
        if (!allowAccessibilityFallback) {
            trace += "I4 无障碍代点：本次调用已关闭该兜底，跳过"
        } else if (!a11yReady) {
            trace += "I4 无障碍代点：无障碍服务未开启，跳过"
        } else {
            AccessibilityBridge.requestInstallConfirm(meta.packageName, ACCESSIBILITY_TIMEOUT_MS)
            trace += "I4 无障碍代点：已受理，重新调起安装器并尝试自动点击「安装」"
            // 有些 ROM 在超时后已经关掉了对话框，这里重发一次保证窗口存在
            val ok = try {
                installerStarted = startSystemInstaller(context, apk, trace) || installerStarted
                awaitInstalled(context, meta, ACCESSIBILITY_TIMEOUT_MS, trace, updatedBefore)
            } finally {
                AccessibilityBridge.cancelInstallConfirm()
            }
            if (ok) {
                trace += "复查通过：无障碍代点完成安装"
                return Outcome(InstallState.INSTALLED, InstallStrategy.ACCESSIBILITY, trace, meta)
            }
            trace += "I4 复查未通过：无障碍未能完成安装确认（部分 ROM 禁止无障碍点击安装器按钮）"
        }

        // 连安装器都调不起来，基本就是缺「安装未知应用」授权 —— 直接把授权页给用户打开
        if (!installerStarted && !unknownSourcesOk) {
            trace += "已跳转「安装未知应用」授权页：开启后再点一次安装即可"
            RunLog.w(TAG, "需要先开启「允许安装未知应用」：${context.packageName}")
            Permissions.openSettings(context, Permissions.unknownSourcesSettingsIntent(context))
        }

        val state = if (installerStarted) {
            InstallState.AWAITING_CONFIRM
        } else {
            InstallState.FAILED
        }
        return Outcome(
            state = state,
            strategy = if (state == InstallState.AWAITING_CONFIRM) {
                InstallStrategy.NOT_CONFIRMED
            } else {
                InstallStrategy.FAILED
            },
            trace = trace,
            meta = meta,
            needsUnknownSources = !unknownSourcesOk,
            needsAccessibility = !a11yReady,
        )
    }

    // ==================== I1 / I2：root 静默安装 ====================

    /** 把 APK 暂存到 /data/local/tmp（system 用户可读），返回暂存路径 */
    private suspend fun stageForRootInstall(apk: File, trace: MutableList<String>): String? {
        val tmpPath = "/data/local/tmp/carassistant_" + System.currentTimeMillis() + ".apk"
        val command = "mkdir -p /data/local/tmp && cp ${shellQuote(apk.absolutePath)} \"$tmpPath\" " +
            "&& chmod 644 \"$tmpPath\""
        val result = ShellRunner.execRoot(command, timeoutMs = 60_000)
        trace += "暂存 APK 到 $tmpPath -> " + (result?.brief() ?: "null")
        return if (result?.isSuccess == true) tmpPath else null
    }

    private suspend fun cleanupRootTemp(tmpPath: String, trace: MutableList<String>) {
        val result = ShellRunner.execRoot("rm -f \"$tmpPath\"", timeoutMs = 10_000)
        trace += "清理暂存文件 -> " + (result?.brief() ?: "null")
    }

    /**
     * 用 root 的 pm install 安装。
     *
     * 返回 null 表示这一级没成功（trace 里已记原因），调用方继续降级。
     */
    private suspend fun rootPmInstall(
        context: Context,
        meta: ApkMeta,
        tmpPath: String,
        trace: MutableList<String>,
        updatedBefore: Long?,
        withUser: Boolean,
    ): Outcome? {
        val label = if (withUser) "I2" else "I1"
        val strategy = if (withUser) InstallStrategy.ROOT_PM_USER else InstallStrategy.ROOT_PM
        val userArg = if (withUser) " --user 0" else ""
        val baseCommand = "pm install$userArg -r -t \"$tmpPath\""

        var result = ShellRunner.execRoot(baseCommand, timeoutMs = 120_000)
        trace += "$label root: $baseCommand -> " + (result?.brief() ?: "null")

        // 目标版本低于本机已装版本时 pm 会拒绝，补 -d 明确允许降级
        if (result != null && isDowngradeFailure(result)) {
            val downgradeCommand = "pm install$userArg -r -t -d \"$tmpPath\""
            result = ShellRunner.execRoot(downgradeCommand, timeoutMs = 120_000)
            trace += "$label root: $downgradeCommand -> " + (result?.brief() ?: "null")
        }

        if (result?.isSuccess == true && verifyInstalled(context, meta, trace, updatedBefore)) {
            return Outcome(InstallState.INSTALLED, strategy, trace, meta)
        }
        return null
    }

    private fun isDowngradeFailure(result: ShellRunner.Result): Boolean {
        val text = result.stdout + "\n" + result.stderr
        return text.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ||
            text.contains("VERSION_DOWNGRADE")
    }

    // ==================== I3：系统安装器 ====================

    /**
     * 用 FileProvider 的 content:// URI 调起系统安装器。
     *
     * ACTION_INSTALL_PACKAGE 是官方入口；个别车机 ROM 只处理 ACTION_VIEW，
     * 所以两条都试。
     */
    private fun startSystemInstaller(
        context: Context,
        apk: File,
        trace: MutableList<String>,
    ): Boolean {
        val uri = runCatching { apkUri(context, apk) }.getOrElse {
            trace += "I3 系统安装器：无法生成 content:// URI（" + it.javaClass.simpleName + "）"
            return false
        }

        // ACTION_INSTALL_PACKAGE 自 API 29 起被标记弃用（官方改推 PackageInstaller 会话式安装），
        // 但会话式安装要求 INSTALL_PACKAGES 系统级权限，纯侧载场景拿不到；
        // 而 ACTION_INSTALL_PACKAGE 在 Android 8~14 上都能正常调起安装器，所以继续用它。
        @Suppress("DEPRECATION")
        val actions = listOf(Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW)

        for (action in actions) {
            try {
                val intent = Intent(action).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_RETURN_RESULT, false)
                }
                context.startActivity(intent)
                trace += "I3 系统安装器：已调起 $action"
                return true
            } catch (e: ActivityNotFoundException) {
                trace += "I3 系统安装器：$action 无接收者（" + e.message + "）"
            } catch (e: Exception) {
                trace += "I3 系统安装器：$action 失败 " + e.javaClass.simpleName + " " + e.message
            }
        }
        return false
    }

    /** APK 文件的 content:// URI，供系统安装器读取 */
    fun apkUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, apk)

    // ==================== 复查 ====================

    /**
     * 轮询复查「包是不是真的装上了」。
     *
     * 不信安装器的启动返回值（它只能说「窗口起来了」），只信 PackageManager：
     * 目标版本必须真的到位才算成功。
     */
    private suspend fun awaitInstalled(
        context: Context,
        meta: ApkMeta,
        timeoutMs: Long,
        trace: MutableList<String>,
        updatedBefore: Long?,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var downgradeNoted = false
        while (System.currentTimeMillis() < deadline) {
            val current = installedVersionCode(context, meta.packageName)
            if (current == meta.versionCode &&
                installedUpdateTime(context, meta.packageName)?.let { it != updatedBefore } == true) return true
            if (current != null && !downgradeNoted && current < meta.versionCode) {
                trace += "系统中存在更低版本 v$current，等待覆盖安装完成…"
                downgradeNoted = true
            }
            delay(1_000)
        }
        return false
    }

    private suspend fun verifyInstalled(
        context: Context,
        meta: ApkMeta,
        trace: MutableList<String>,
        updatedBefore: Long?,
    ): Boolean {
        // root 安装是同步返回的，但 installd 落盘有延迟，给几秒缓冲
        repeat(6) { attempt ->
            val current = installedVersionCode(context, meta.packageName)
            if (current == meta.versionCode &&
                installedUpdateTime(context, meta.packageName)?.let { it != updatedBefore } == true) {
                trace += "复查通过：${meta.packageName} 当前版本 v$current"
                return true
            }
            if (attempt == 5) {
                trace += if (current == null) {
                    "复查未通过：系统里查不到 ${meta.packageName}"
                } else {
                    "复查未通过：系统里仍是 v$current（目标 v" + meta.versionCode + "）"
                }
            } else {
                delay(500)
            }
        }
        return false
    }

    // ==================== 读取 APK 元信息 ====================

    /**
     * 从 APK 文件解析包名/版本/应用名。
     *
     * 文件还没安装，所以必须走 getPackageArchiveInfo；解析不到就返回 null
     * （损坏的文件，或被 minSdk/ABI 挡住的包），交给调用方如实报告。
     */
    fun readMeta(context: Context, apk: File): ApkMeta? = runCatching {
        val pm = context.packageManager
        val info = archiveInfo(pm, apk.absolutePath) ?: return null
        val app = info.applicationInfo ?: return null
        // 未安装的 APK 必须手动补上这两个路径，否则 loadLabel 拿不到资源
        app.sourceDir = apk.absolutePath
        app.publicSourceDir = apk.absolutePath
        ApkMeta(
            packageName = info.packageName,
            versionCode = versionCodeOf(info),
            versionName = info.versionName.orEmpty(),
            label = runCatching { pm.getApplicationLabel(app).toString() }
                .getOrDefault(info.packageName),
        )
    }.getOrNull()

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        }

    private fun installedUpdateTime(context: Context, packageName: String): Long? =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrNull()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** 已安装包的版本号；未安装返回 null */
    fun installedVersionCode(context: Context, packageName: String): Long? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        versionCodeOf(info)
    }.getOrNull()

    /** longVersionCode 从 API 28 起才有；车机上还有 Android 8/9，必须兼容 */
    fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    // ==================== 能力自检 ====================

    /** 安装/下载相关的能力快照（与 WiFi 自检分开，避免互相拖慢） */
    suspend fun env(context: Context, allowRoot: Boolean = true): InstallEnvSnapshot =
        withContext(Dispatchers.IO) {
            InstallEnvSnapshot(
                canInstallUnknownSources = Permissions.canInstallPackages(context),
                hasReadStorage = Permissions.hasReadStorage(context),
                accessibilityReady = AccessibilityBridge.isServiceReady(),
                rootAvailable = allowRoot &&
                    ShellRunner.detectRoot() is ShellRunner.RootStatus.Available,
            )
        }
}
