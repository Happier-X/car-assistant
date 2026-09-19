package com.carassistant.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carassistant.core.AccessibilityBridge
import com.carassistant.core.AppLauncher
import com.carassistant.core.AppUninstaller
import com.carassistant.core.BootFlowOrchestrator
import com.carassistant.core.CapabilitySnapshot
import com.carassistant.core.LaunchTarget
import com.carassistant.core.Permissions
import com.carassistant.core.RunLog
import com.carassistant.core.ShellRunner
import com.carassistant.core.WifiController
import com.carassistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 ViewModel。
 *
 * 所有业务状态都在这里聚合，UI 只负责渲染 —— 这样车机上的
 * 屏幕旋转/切后台再回来都不会丢状态。
 */
class MainViewModel(
    private val appContext: Context,
) : ViewModel() {

    private val repository = SettingsRepository(appContext)

    /** 配置（来自 DataStore，自动响应变化） */
    val settings: StateFlow<SettingsRepository.Settings> = repository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsRepository.Settings(),
        )

    /** 设备能力快照 */
    private val _capabilities = MutableStateFlow<CapabilitySnapshot?>(null)
    val capabilities: StateFlow<CapabilitySnapshot?> = _capabilities.asStateFlow()

    /** 可启动应用列表（供选择器） */
    private val _availableApps = MutableStateFlow<List<AppLauncher.AppEntry>>(emptyList())
    val availableApps: StateFlow<List<AppLauncher.AppEntry>> = _availableApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    /** 应用搜索关键词 */
    private val _appQuery = MutableStateFlow("")
    val appQuery: StateFlow<String> = _appQuery.asStateFlow()

    /** 过滤后的应用列表 */
    val filteredApps: StateFlow<List<AppLauncher.AppEntry>> =
        combine(_availableApps, _appQuery) { apps, query ->
            if (query.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** 流程执行状态 */
    val flowState: StateFlow<BootFlowOrchestrator.State> = BootFlowOrchestrator.state

    /** 最近一次的执行报告，用于界面顶部卡片 */
    private val _lastReport = MutableStateFlow<com.carassistant.core.BootReport?>(null)
    val lastReport: StateFlow<com.carassistant.core.BootReport?> = _lastReport.asStateFlow()

    /** 日志行 */
    val logLines: StateFlow<List<String>> = RunLog.lines

    init {
        refreshCapabilities()
        loadApps()
    }

    // ==================== 能力自检 ====================

    fun refreshCapabilities() {
        viewModelScope.launch {
            _capabilities.value = withContext(Dispatchers.IO) {
                WifiController.snapshot(appContext)
            }
        }
    }

    // ==================== 应用列表 ====================

    fun loadApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = withContext(Dispatchers.IO) {
                AppLauncher.listLaunchableApps(appContext)
            }
            _availableApps.value = apps
            _isLoadingApps.value = false
            RunLog.i("UI", "扫描到 ${apps.size} 个应用")
        }
    }

    fun setAppQuery(query: String) {
        _appQuery.value = query
    }

    fun addTarget(entry: AppLauncher.AppEntry) {
        viewModelScope.launch {
            repository.addTarget(
                LaunchTarget(
                    packageName = entry.packageName,
                    label = entry.label,
                    waitBeforeMs = if (settings.value.targets.isEmpty()) 0 else 3_000,
                )
            )
            RunLog.i("UI", "已添加启动项：${entry.label}")
        }
    }

    fun removeTarget(packageName: String) {
        viewModelScope.launch {
            repository.removeTarget(packageName)
            RunLog.i("UI", "已移除启动项：$packageName")
        }
    }

    fun setTargetEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch { repository.setTargetEnabled(packageName, enabled) }
    }

    fun setTargetWait(packageName: String, waitMs: Int) {
        viewModelScope.launch { repository.setTargetWait(packageName, waitMs) }
    }

    fun moveTargetToTop(packageName: String) {
        viewModelScope.launch { repository.moveTargetToTop(packageName) }
    }

    fun moveTargetUp(packageName: String) {
        viewModelScope.launch { repository.moveTargetUp(packageName) }
    }

    fun moveTargetDown(packageName: String) {
        viewModelScope.launch { repository.moveTargetDown(packageName) }
    }

    /**
     * 卸载应用。
     *
     * 完成后刷新应用列表和自检（被卸载的包从列表中消失）。
     * 如果被卸载的包正好在启动项里，也一并移除，避免开机时
     * 白白等待一个不存在的应用。
     */
    fun uninstallApp(packageName: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val snapshot = settings.value
            val outcome = withContext(Dispatchers.IO) {
                AppUninstaller.uninstall(appContext, packageName, snapshot.allowRoot)
            }
            outcome.trace.forEach { RunLog.i("Uninstall", "  $it") }
            if (outcome.success) {
                RunLog.ok("卸载成功：$packageName（${outcome.strategy}）")
                repository.removeTarget(packageName)
                loadApps()
            } else {
                RunLog.fail("卸载失败：$packageName（${outcome.strategy}）")
            }
            refreshCapabilities()
            onResult(outcome.success, outcome.strategy)
        }
    }

    // ==================== 设置项 ====================

    fun setTriggerOnBoot(value: Boolean) = launchWrite { repository.setTriggerOnBoot(value) }

    fun setTriggerOnScreenOn(value: Boolean) = launchWrite { repository.setTriggerOnScreenOn(value) }

    fun setTriggerOnPowerConnect(value: Boolean) =
        launchWrite { repository.setTriggerOnPowerConnect(value) }

    fun setBootDelayMs(value: Int) = launchWrite { repository.setBootDelayMs(value) }

    fun setWifiEnabled(value: Boolean) = launchWrite { repository.setWifiEnabled(value) }

    fun setWifiUiFallback(value: Boolean) = launchWrite { repository.setWifiUiFallback(value) }

    fun setWifiVerifyTimeoutMs(value: Int) =
        launchWrite { repository.setWifiVerifyTimeoutMs(value) }

    fun setLaunchEnabled(value: Boolean) = launchWrite { repository.setLaunchEnabled(value) }

    fun setOpenSelfOnBoot(value: Boolean) = launchWrite { repository.setOpenSelfOnBoot(value) }

    fun setAllowRoot(value: Boolean) = launchWrite { repository.setAllowRoot(value) }

    fun setVerifyLaunch(value: Boolean) = launchWrite { repository.setVerifyLaunch(value) }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ==================== 动作 ====================

    /** 立即执行一次完整流程（忽略去重窗口） */
    fun runFlowNow() {
        RunLog.step("用户手动触发完整流程测试")
        BootFlowOrchestrator.trigger(appContext, "MANUAL_TEST", force = true) { report ->
            _lastReport.value = report
        }
    }

    /** 只测试开 WiFi */
    fun testWifiOnly() {
        viewModelScope.launch {
            RunLog.step("--- 单独测试 WiFi 开启 ---")
            val snapshot = settings.value
            val outcome = WifiController.ensureEnabled(
                context = appContext,
                allowRoot = snapshot.allowRoot,
                allowUiFallback = snapshot.wifiUiFallback,
                timeoutMs = snapshot.wifiVerifyTimeoutMs.toLong(),
            )
            if (outcome.success) {
                RunLog.ok("WiFi 测试成功：${outcome.summary}")
            } else {
                RunLog.fail("WiFi 测试失败")
            }
            outcome.trace.forEach { RunLog.i("WifiTest", "  $it") }
            refreshCapabilities()
        }
    }

    /** 重新探测 root（用户可能在两次点击之间授权了） */
    fun redetectRoot() {
        viewModelScope.launch {
            ShellRunner.resetRootCache()
            val status = ShellRunner.detectRoot(force = true)
            RunLog.i("UI", "重新探测 root：$status")
            refreshCapabilities()
        }
    }

    fun openIntent(intent: Intent) {
        Permissions.openSettings(appContext, intent)
    }

    fun clearLog() {
        RunLog.clear()
    }

    /** 导出当前窗口控件树（用于分析车机 ROM 的设置页结构） */
    fun dumpActiveWindow() {
        viewModelScope.launch {
            val dump = withContext(Dispatchers.Default) {
                AccessibilityBridge.dumpActiveWindow()
            }
            RunLog.step("--- 当前窗口控件树 ---")
            dump.lineSequence().forEach { RunLog.i("WindowDump", it) }
        }
    }

    fun copyLogToClipboard(): String = RunLog.dump()
}