package com.carassistant.core

/**
 * 无障碍服务的桥接层。
 *
 * 服务在 manifest 里被系统实例化，而启动流程需要调它的静态能力。
 * 直接双向依赖会让 core 包反向依赖 com.carassistant 包，形成环。
 * 这里用一个注册表解耦：服务在 onServiceConnected 时注册进来，
 * core 层只依赖这个接口。
 */
object AccessibilityBridge {

    /**
     * 服务实现的能力接口。
     * 之所以不让 core 直接引用 AccessibilityService 子类，是为了
     * 让 core 包可以独立测试，也避免循环依赖。
     */
    interface Handler {
        /** 服务是否可用 */
        val isReady: Boolean

        /** 当前窗口所属包名（前台探测器） */
        fun currentForegroundPackage(): String?

        /** 代 App 执行 startActivity —— 不受后台启动限制 */
        fun launchPackage(packageName: String): Boolean

        /** 排入一个「点击 WiFi 开关」的任务，返回是否成功受理 */
        fun requestWifiToggle(timeoutMs: Long): Boolean

        /** 取消未完成的 WiFi 点击任务 */
        fun cancelWifiToggle()

        /** 导出当前窗口的控件树（诊断用） */
        fun dumpActiveWindow(): String
    }

    @Volatile
    private var handler: Handler? = null

    fun register(handler: Handler) {
        this.handler = handler
    }

    fun unregister() {
        handler = null
    }

    fun isServiceReady(): Boolean = handler?.isReady == true

    fun currentPackage(): String? = handler?.currentForegroundPackage()

    fun launchPackage(packageName: String): Boolean =
        handler?.launchPackage(packageName) ?: false

    fun requestWifiToggle(timeoutMs: Long = 12_000): Boolean =
        handler?.requestWifiToggle(timeoutMs) ?: false

    fun cancelWifiToggle() {
        handler?.cancelWifiToggle()
    }

    fun dumpActiveWindow(): String =
        handler?.dumpActiveWindow() ?: "无障碍服务未连接"
}