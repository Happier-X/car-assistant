package com.carassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.carassistant.core.AccessibilityBridge
import com.carassistant.core.RunLog

/**
 * 无障碍服务 —— 本方案里的「万能兜底」。
 *
 * ## 承担的四件事
 *
 * 1. **代启动应用**：AccessibilityService 的 `startActivity()` 不受
 *    Android 10+ 后台启动限制约束。这是无 root 场景下，开机自动拉起
 *    亿连最可靠的手段。
 *
 * 2. **代点 WiFi 开关**：所有静默开 WiFi 的手段都失败时，拉起系统设置页
 *    然后在这里自动找到开关并点击，作为最后一招。
 *
 * 3. **前台包名探测**：`onAccessibilityEvent` 里记录最近一个
 *    WINDOW_STATE_CHANGED 的包名，供启动流程校验「App 是否真的起来了」。
 *    这是无 root 时唯一可用的前台检测手段。
 *
 * 4. **代点安装确认**：无 root 时系统安装器要求用户手动点「安装」，
 *    车机屏幕小、按钮偏，这里按文本匹配自动点击（安装链 I4 兜底）。
 *    该能力严格限定在安装器/设置窗口内生效，绝不会在用户自己的界面里乱点。
 *
 * ## 为什么 UI 点击要做三轮匹配
 *
 * 车机 ROM 的设置页实现千差万别：有的用标准 `switch_widget` id，
 * 有的把开关做成自定义 View，有的连 ViewId 都没有。所以要按
 * 「标准 id → 文本关键词附近找 → 任意未选中的 checkable」逐轮降级。
 */
class AssistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11y"

        /**
         * 安装确认按钮的候选文案（**完全相等**匹配，不用 contains）。
         *
         * 覆盖 AOSP 安装器与常见 OEM 的中英文文案。
         * 刻意不包含「确定 / OK」：这两个词在设置页等通用弹窗里太常见，
         * 万一代点时窗口切到了设置页，容易误点。宁可少点一次，也不能乱点。
         */
        private val INSTALL_CONFIRM_TEXTS = listOf(
            "安装", "继续安装", "仍要安装", "仍然安装", "下一步",
            "Install", "INSTALL", "Continue", "Install anyway", "Next",
        )
    }

    private val handler = object : AccessibilityBridge.Handler {
        override val isReady: Boolean get() = true

        override fun currentForegroundPackage(): String? = currentPackage

        override fun launchPackage(packageName: String): Boolean = startPackage(packageName)

        override fun requestWifiToggle(timeoutMs: Long): Boolean {
            wifiToggleDeadline = System.currentTimeMillis() + timeoutMs
            pendingWifiToggle = true
            RunLog.i(TAG, "已受理 WiFi 开关点击任务（${timeoutMs}ms 内完成）")
            return true
        }

        override fun cancelWifiToggle() {
            pendingWifiToggle = false
        }

        override fun requestInstallConfirm(packageName: String, timeoutMs: Long): Boolean {
            installConfirmTarget = packageName
            installConfirmDeadline = System.currentTimeMillis() + timeoutMs
            pendingInstallConfirm = true
            RunLog.i(TAG, "已受理安装确认点击任务（目标 $packageName，${timeoutMs}ms 内完成）")
            return true
        }

        override fun cancelInstallConfirm() {
            pendingInstallConfirm = false
        }

        override fun dumpActiveWindow(): String = buildWindowDump()
    }

    // ---- 前台包名探测 ----
    @Volatile
    private var currentPackage: String = ""

    // ---- 待办的 WiFi 点击任务 ----
    @Volatile
    private var pendingWifiToggle = false
    @Volatile
    private var wifiToggleDeadline = 0L

    // ---- 待办的安装确认点击任务 ----
    @Volatile
    private var pendingInstallConfirm = false
    @Volatile
    private var installConfirmDeadline = 0L
    @Volatile
    private var installConfirmTarget = ""

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 车机 ROM 差异太大，直接配最宽的事件范围
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        AccessibilityBridge.register(handler)
        RunLog.ok("无障碍服务已连接 —— 启动兜底与查漏补缺能力就绪")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        pendingWifiToggle = false
        pendingInstallConfirm = false
        AccessibilityBridge.unregister()
        RunLog.w(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    // ==================== 事件处理 ====================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 记录前台包名
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackage = it }
        }

        if (pendingWifiToggle) {
            if (System.currentTimeMillis() > wifiToggleDeadline) {
                pendingWifiToggle = false
                RunLog.fail("无障碍点击 WiFi 开关超时，放弃")
            } else if (tryToggleWifiSwitch()) {
                pendingWifiToggle = false
                RunLog.ok("无障碍已点开 WiFi 开关")
                goHome()
            }
        }

        if (pendingInstallConfirm) {
            handleInstallConfirm(event)
        }
    }

    // ==================== 代点安装确认 ====================

    /**
     * 安装确认界面的自动点击（安装链 I4 兜底）。
     *
     * ## 只在安装器类窗口里动手
     *
     * 这个服务在整机范围内都能点击，所以必须严格限定作用域：只有当前窗口
     * 属于已知安装器时才去找按钮，否则用户在自己的界面里
     * 操作时会被误点。按钮文案用完全相等匹配，避免误命中含「安装」二字的普通文本。
     *
     * ## 为什么一轮只点一个
     *
     * Android 8+ 的安装流程常常是两步：先「继续安装」，再「安装」。
     * 每轮窗口事件只点一个按钮，等下一个事件再点下一个 ——
     * 这样即使两步之间弹出了别的东西，也不会连着点到「取消」。
     *
     * ## 如实报告
     *
     * 部分 ROM（尤其 Android 12+）会忽略无障碍服务对安装器按钮的点击。
     * 这里点了不代表装上了，最终以 [ApkInstaller] 的 PackageManager 复查为准。
     */
    private fun handleInstallConfirm(event: AccessibilityEvent) {
        if (System.currentTimeMillis() > installConfirmDeadline) {
            pendingInstallConfirm = false
            RunLog.fail("无障碍代点安装确认超时，放弃")
            return
        }

        val pkg = event.packageName?.toString().orEmpty()
        if (!isInstallerPackage(pkg)) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val button = findButtonByText(root, INSTALL_CONFIRM_TEXTS) ?: return
        val clicked = performClick(button)
        RunLog.i(TAG, "无障碍点击安装确认按钮（$pkg）：$clicked")
    }

    /**
     * 是否属于「安装器类」窗口。
     *
     * 只接受已知安装器包名；未知 ROM 保留手动确认，避免误点设置页或其它应用。
     */
    private fun isInstallerPackage(pkg: String): Boolean =
        pkg in setOf(
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.miui.packageinstaller", "com.samsung.android.packageinstaller",
        )

    /** 找文本完全匹配的按钮（performClick 会自动向上找可点的父容器） */
    private fun findButtonByText(
        root: AccessibilityNodeInfo,
        texts: List<String>,
    ): AccessibilityNodeInfo? = findFirst(root) { node ->
        val text = node.text?.toString()?.trim() ?: return@findFirst false
        texts.any { it.equals(text, ignoreCase = true) }
    }

    // ==================== WiFi 开关点击 ====================

    /**
     * 在当前窗口里找 WiFi 开关并点击。
     * 三轮匹配，逐级放宽条件。
     */
    private fun tryToggleWifiSwitch(): Boolean {
        val root = rootInActiveWindow ?: return false

        val target = findSwitchByStandardId(root)
            ?: findSwitchNearLabel(root)
            ?: findAnyUncheckedSwitch(root)
            ?: return false

        val clicked = performClick(target)
        RunLog.i(TAG, "无障碍点击 WiFi 开关：$clicked")
        return clicked
    }

    /** 轮 1：按标准 ViewId 找 */
    private fun findSwitchByStandardId(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val knownIds = listOf(
            "${packageName}:id/switch_widget",
            "com.android.settings:id/switch_widget",
            "com.android.settings:id/switchWidget",
            "android:id/switch_widget",
            "com.android.settings:id/wifi_switch",
        )
        return knownIds.firstNotNullOfOrNull { id ->
            findFirst(root) { it.viewIdResourceName == id }
        }
    }

    /** 轮 2：在「WLAN / Wi-Fi / 无线局域网」文本所在的行里找开关 */
    private fun findSwitchNearLabel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val keywords = listOf("WLAN", "Wi-Fi", "WiFi", "无线局域网", "无线网络")

        val label = findFirst(root) { node ->
            val text = node.text?.toString()?.trim() ?: return@findFirst false
            keywords.any { text.equals(it, ignoreCase = true) || text.startsWith(it, ignoreCase = true) }
        } ?: return null

        // 从标签往上最多 5 层，每层找一次开关控件
        var ancestor = label.parent
        var depth = 0
        while (ancestor != null && depth++ < 5) {
            findSwitchIn(ancestor)?.let { return it }
            ancestor = ancestor.parent
        }
        return null
    }

    /** 轮 3：兜底，任意「可选但未选中」的控件 */
    @Suppress("DEPRECATION")
    private fun findAnyUncheckedSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { it.isCheckable && !it.isChecked }

    private fun findSwitchIn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(node) { candidate ->
            val className = candidate.className?.toString().orEmpty()
            val viewId = candidate.viewIdResourceName.orEmpty()
            className.contains("Switch", ignoreCase = true) ||
                className.contains("Toggle", ignoreCase = true) ||
                viewId.contains("switch", ignoreCase = true) ||
                candidate.isCheckable
        }

    /**
     * 点击控件。
     *
     * 有些 ROM 的 Switch 自身不可点，必须点父容器 —— 所以要回溯向上找可点节点。
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var ancestor = node.parent
        var depth = 0
        while (ancestor != null && depth++ < 4) {
            if (ancestor.isClickable && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            ancestor = ancestor.parent
        }
        return false
    }

    /** 点完开关回桌面，避免设置页挡住后续要启动的 App */
    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            RunLog.i(TAG, "已回到桌面")
        }
    }

    // ==================== 代启动应用 ====================

    /**
     * 代 App 启动目标包。
     *
     * 优先用 getLaunchIntentForPackage；拿不到就自己拼 MAIN/LAUNCHER intent
     * （车机上的车机版应用常常没有标准 launch intent）。
     */
    private fun startPackage(packageName: String): Boolean = runCatching {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        startActivity(intent)
        true
    }.getOrElse {
        RunLog.e(TAG, "无障碍启动 $packageName 失败", it)
        false
    }

    // ==================== 控件树遍历工具 ====================

    /** 深度优先查找第一个满足条件的节点 */
    private fun findFirst(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (runCatching { predicate(node) }.getOrDefault(false)) return node

        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            findFirst(child, predicate)?.let { return it }
        }
        return null
    }

    /** 导出当前窗口控件树，诊断页用来分析车机 ROM 的设置页结构 */
    private fun buildWindowDump(): String {
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return "无法获取当前窗口（可能没有活动的窗口）"

        val builder = StringBuilder()
        dumpNode(root, builder, depth = 0)
        return builder.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, builder: StringBuilder, depth: Int) {
        node ?: return
        if (depth > 12 || builder.length > 12_000) return

        builder.append("  ".repeat(depth))
        builder.append(node.className?.toString()?.substringAfterLast('.') ?: "?")
        node.viewIdResourceName?.let { builder.append("  #").append(it.substringAfterLast('/')) }
        node.text?.let { builder.append("  text=\"").append(it).append('"') }
        // isChecked 在 API 36 起被弃用（推荐用 stateDescription），
        // 但车机多为 Android 9~12，旧接口兼容性更好，且这里仅用于调试导出
        @Suppress("DEPRECATION")
        if (node.isCheckable) builder.append("  [checkable checked=${node.isChecked}]")
        if (node.isClickable) builder.append("  [clickable]")
        builder.append('\n')

        for (index in 0 until node.childCount) {
            dumpNode(runCatching { node.getChild(index) }.getOrNull(), builder, depth + 1)
        }
    }
}