package com.carassistant.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carassistant.core.AccessibilityBridge
import com.carassistant.core.ApkDownloader
import com.carassistant.core.ApkInstaller
import com.carassistant.core.AppLauncher
import com.carassistant.core.AppUninstaller
import com.carassistant.core.BootFlowOrchestrator
import com.carassistant.core.CapabilitySnapshot
import com.carassistant.core.InstallEnvSnapshot
import com.carassistant.core.InstallMessage
import com.carassistant.core.InstallState
import com.carassistant.core.LaunchTarget
import com.carassistant.core.LocalApkScanner
import com.carassistant.core.MessageLevel
import com.carassistant.core.Permissions
import com.carassistant.core.PushServer
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主界面 ViewModel。
 *
 * 所有业务状态都在这里聚合，UI 只负责渲染 —— 这样车机上的
 * 屏幕旋转/切后台再回来都不会丢状态。
 */
class MainViewModel(
    private val appContext: Context,
) : ViewModel() {

    // 卸载防重入标志（车机双击误触时，两次调用间隔只有几毫秒）
    private val uninstallInFlight = AtomicBoolean(false)

    // 安装防重入标志（同上）
    private val installInFlight = AtomicBoolean(false)

    /** APK 下载器（状态自持，UI 只 collect） */
    private val downloader = ApkDownloader(appContext)

    /** 下载状态：进度、速率、结果 */
    val downloadState: StateFlow<ApkDownloader.Snapshot> = downloader.state

    /**
     * 手机推送服务。
     *
     * 主路径是「手机浏览器上传 APK」：车机开一个只绑局域网地址的小 HTTP 服务，
     * 手机不需要装任何 App。直链下载保留为次要入口。
     */
    private val pushServer = PushServer(appContext)

    /** 推送服务状态（访问地址、端口、接收进度、最近一次上传） */
    val pushState: StateFlow<PushServer.Snapshot> = pushServer.state
    private val bleTester = com.carassistant.core.BleAdvertiserTest(appContext)
    val bleTestState = bleTester.state
    fun startBleTest() = bleTester.start()
    fun stopBleTest(reason: String = "用户停止") = bleTester.stop(reason)

    /**
     * 第二种广播模式：可连接 / 可扫描广播（startAdvertisingSet）。
     * 与上面的对照模式（startAdvertising + connectable=false）各自独立门禁、独立状态；
     * 两个模式可以分别跑，结果不会互相污染。
     */
    private val bleConnectableTester = com.carassistant.core.BleConnectableAdvertiserTest(appContext)
    val bleConnectableState = bleConnectableTester.state
    fun startBleConnectableTest() = bleConnectableTester.start()
    fun stopBleConnectableTest(reason: String = "用户停止") = bleConnectableTester.stop(reason)

    /** 可连接模式的报告字段（含两种模式的对照结论）；对照模式证据只读，不参与本模式判定。 */
    fun bleConnectableFields(): List<com.carassistant.core.CarDiagnostics.Field> =
        bleConnectableState.value.reportFields(
            plain = com.carassistant.core.BleDirection.advertiseState(bleTestState.value.phase),
            capability = com.carassistant.core.BleConnectableAdvertiserTest.capabilityFields(appContext),
        )

    private fun connectableAdvSection(): String = com.carassistant.core.BleConnectableAdv.exportSection(
        plain = com.carassistant.core.BleDirection.advertiseState(bleTestState.value.phase),
        mode = bleConnectableState.value.mode,
        callbacks = bleConnectableState.value.events,
        actualParams = bleConnectableState.value.actualParams,
        result = bleConnectableState.value.result,
        error = bleConnectableState.value.error,
        cleanup = bleConnectableState.value.cleanup,
    )
    private val bleScanner = com.carassistant.core.BleScanner(appContext)
    val bleScanState = bleScanner.state
    fun setScanForeground(value: Boolean) = bleScanner.setForeground(value)
    fun startBleScan() = bleScanner.start()
    fun stopBleScan() = bleScanner.stop()
    fun clearBleScan() = bleScanner.clear()

    /**
     * 手机侧观察登记：本应用不能自动采集，只能记录用户自述。
     * 只保存在内存，显式导出时才进入报告；默认导出会脱敏。
     */
    private val _phoneObservation = MutableStateFlow(com.carassistant.core.BleDirection.PhoneObservation())
    val phoneObservation = _phoneObservation.asStateFlow()

    fun updatePhoneObservation(
        outcome: com.carassistant.core.BleDirection.PhoneOutcome = _phoneObservation.value.outcome,
        model: String = _phoneObservation.value.model,
        tool: String = _phoneObservation.value.tool,
        observedAt: String = _phoneObservation.value.observedAt,
        note: String = _phoneObservation.value.note,
    ) {
        _phoneObservation.value = com.carassistant.core.BleDirection.PhoneObservation.of(outcome, model, tool, observedAt, note)
    }

    /** 可修改、可清除；清除只动登记，不动已保存的扫描结果。 */
    fun clearPhoneObservation() {
        _phoneObservation.value = com.carassistant.core.BleDirection.PhoneObservation()
    }

    /** 当前方向的判定（发送 / 接收分开，缺失即未知）。 */
    fun bleDirectionVerdict(): com.carassistant.core.BleDirection.Verdict =
        com.carassistant.core.BleDirection.verdict(
            advertise = com.carassistant.core.BleDirection.advertiseState(bleTestState.value.phase),
            observation = _phoneObservation.value,
            scan = ownScan(),
        )

    /** 本机扫描证据（只给接收方向用）：是否发起过、几条、当前状态文本。 */
    private fun ownScan() = com.carassistant.core.BleDirection.OwnScan(
        attempted = bleScanState.value.attempted,
        rows = bleScanState.value.rows.size,
        message = bleScanState.value.message,
    )

    private fun bleDirectionSection(): String = com.carassistant.core.BleDirection.report(
        advertise = com.carassistant.core.BleDirection.advertiseState(bleTestState.value.phase),
        observation = _phoneObservation.value,
        scan = ownScan(),
    )
    private val _importedReports = MutableStateFlow<List<com.carassistant.core.ConnectionEvidence.Report?>>(listOf(null, null))
    val importedReports = _importedReports.asStateFlow()
    private val _importMessage = MutableStateFlow("")
    val importMessage = _importMessage.asStateFlow()
    private val _importBusy = MutableStateFlow(false)
    val importBusy = _importBusy.asStateFlow()
    private var pendingConnectionExport: String? = null
    private val _connectionExportMessage = MutableStateFlow("")
    val connectionExportMessage = _connectionExportMessage.asStateFlow()
    private val _connectionExportBusy = MutableStateFlow(false)
    val connectionExportBusy = _connectionExportBusy.asStateFlow()
    fun prepareConnectionExport(raw: Boolean) {
        bleScanner.stop("用户保存扫描快照")
        pendingConnectionExport = bleScanState.value.report(raw) + "\n\n" + bleDirectionSection() + "\n\n" + connectableAdvSection() + "\n\n" + connectionReportSummary()
    }
    fun saveConnectionExport(uri: android.net.Uri?) {
        val text = pendingConnectionExport
        pendingConnectionExport = null
        if (uri == null) return
        if (text == null) { _connectionExportMessage.value = "待保存内容已失效（可能进程已重启），请重新保存。"; return }
        _connectionExportBusy.value = true
        viewModelScope.launch {
            _connectionExportMessage.value = try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("输出文件不可写")
                }
                "已保存 UTF-8 诊断文本；请自行保管或删除副本。"
            } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}；请重新选择可写位置。" }
            _connectionExportBusy.value = false
        }
    }
    fun importConnectionReport(slot: Int, uri: android.net.Uri) {
        if (slot !in 0..1 || _importBusy.value) return
        _importBusy.value = true
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val text = appContext.contentResolver.openInputStream(uri)?.use { com.carassistant.core.ConnectionEvidence.read(it) }
                        ?: error("文件无法读取")
                    com.carassistant.core.ConnectionEvidence.parse(text)
                }
                _importedReports.value = _importedReports.value.toMutableList().also { it[slot] = report }
                _importMessage.value = "来源 ${if (slot == 0) "A" else "B"} 已导入；原文件未修改，仅保存在进程内。"
            } catch (e: Exception) {
                _importMessage.value = "导入失败：${e.javaClass.simpleName}" + if (e is IllegalArgumentException) " / ${e.message}" else "；请检查文件权限与 UTF-8/带 BOM 的 UTF-16 编码。"
            } finally { _importBusy.value = false }
        }
    }
    fun clearConnectionReports() { if (!_importBusy.value) _importedReports.value = listOf(null, null) }
    fun connectionReportSummary(): String = buildString {
        appendLine("导入报告分析（默认省略原文、源路径与自定义名称）")
        _importedReports.value.forEachIndexed { i, r -> if (r != null) appendLine(r.summary(if (i == 0) "A" else "B")) }
        appendLine(com.carassistant.core.ConnectionEvidence.compare(_importedReports.value[0], _importedReports.value[1]))
    }
    private val wifiDirect = com.carassistant.core.WifiDirectController(appContext)
    val wifiDirectState = wifiDirect.state
    fun enterWifiDirect() = wifiDirect.enter()
    fun leaveWifiDirect() = wifiDirect.leave()
    fun refreshWifiDirect() {
        if (wifiDirectState.value.observing) wifiDirect.refresh() else wifiDirect.enter()
    }
    fun actWifiDirect(kind: String, peerId: Long? = null) = wifiDirect.act(kind, peerId)

    private val _diagnostics = MutableStateFlow<com.carassistant.core.CarDiagnostics.Snapshot?>(null)
    val diagnostics = _diagnostics.asStateFlow()
    private val _diagnosticBusy = MutableStateFlow(false)
    val diagnosticBusy = _diagnosticBusy.asStateFlow()
    private val _diagnosticReport = MutableStateFlow<String?>(null)
    val diagnosticReport = _diagnosticReport.asStateFlow()
    private val _diagnosticReportPublished = MutableStateFlow(false)
    val diagnosticReportPublished = _diagnosticReportPublished.asStateFlow()
    private var reportExpiry: kotlinx.coroutines.Job? = null
    private var diagnosticRevision = 0L
    private var reportDeadlineMillis = 0L

    fun bleDirectionFields(): List<com.carassistant.core.CarDiagnostics.Field> = com.carassistant.core.BleDirection.reportFields(
        advertise = com.carassistant.core.BleDirection.advertiseState(bleTestState.value.phase),
        observation = _phoneObservation.value,
        scan = ownScan(),
    )

    fun expireDiagnosticReportIfNeeded() {
        if (_diagnosticReport.value != null && android.os.SystemClock.elapsedRealtime() >= reportDeadlineMillis) {
            _diagnosticReport.value = null
            _diagnosticReportPublished.value = false
            reportExpiry?.cancel()
            pushServer.clearDiagnosticReport()
        }
    }

    fun refreshDiagnostics(generateReport: Boolean = false) {
        if (_diagnosticBusy.value) return
        _diagnosticBusy.value = true
        reportExpiry?.cancel()
        _diagnosticReport.value = null
        _diagnosticReportPublished.value = false
        val serverRevision = pushServer.clearDiagnosticReport()
        val revision = ++diagnosticRevision
        viewModelScope.launch {
            try {
                val collected = withContext(Dispatchers.IO) {
                    com.carassistant.core.CarDiagnostics.collect(appContext)
                }
                val snapshot = collected.copy(fields = collected.fields + bleTestState.value.reportFields() + bleDirectionFields() + bleConnectableFields() + wifiDirectState.value.reportFields())
                _diagnostics.value = snapshot
                if (generateReport && revision == diagnosticRevision) {
                    val report = snapshot.report() + "\n\n" + bleScanState.value.report() + "\n\n" + bleDirectionSection() + "\n\n" + connectableAdvSection() + "\n\n" + connectionReportSummary()
                    _diagnosticReport.value = report
                    reportDeadlineMillis = android.os.SystemClock.elapsedRealtime() + 10 * 60 * 1000L
                    _diagnosticReportPublished.value = pushServer.publishDiagnosticReport(report, serverRevision)
                    reportExpiry = viewModelScope.launch {
                        kotlinx.coroutines.delay(10 * 60 * 1000L)
                        _diagnosticReport.value = null
                        _diagnosticReportPublished.value = false
                        pushServer.clearDiagnosticReport()
                    }
                }
            } finally {
                _diagnosticBusy.value = false
            }
        }
    }

    /** 下载完成后解析出的 APK 元信息（装之前告诉用户这是什么包） */
    private val _downloadedMeta = MutableStateFlow<ApkInstaller.ApkMeta?>(null)
    val downloadedMeta: StateFlow<ApkInstaller.ApkMeta?> = _downloadedMeta.asStateFlow()

    /** 扫描到的本机 / U 盘 APK 列表 */
    private val _localApks = MutableStateFlow<List<LocalApkScanner.Entry>>(emptyList())
    val localApks: StateFlow<List<LocalApkScanner.Entry>> = _localApks.asStateFlow()

    private val _isScanningApks = MutableStateFlow(false)
    val isScanningApks: StateFlow<Boolean> = _isScanningApks.asStateFlow()

    /** 安装执行中（UI 用它禁止重复点击 / 关闭弹窗） */
    private val _installBusy = MutableStateFlow(false)
    val installBusy: StateFlow<Boolean> = _installBusy.asStateFlow()

    /** 安装/下载能力自检（未知来源授权、存储权限、无障碍、root） */
    private val _installEnv = MutableStateFlow<InstallEnvSnapshot?>(null)
    val installEnv: StateFlow<InstallEnvSnapshot?> = _installEnv.asStateFlow()

    /** 最近一次下载/安装的结果提示 */
    private val _installMessage = MutableStateFlow<InstallMessage?>(null)
    val installMessage: StateFlow<InstallMessage?> = _installMessage.asStateFlow()

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
        refreshInstallEnv()

        // 手机推送完成 → 先记日志，再走与本地文件完全相同的安装链
        pushServer.onUploaded = { file, size, clientIp, costMs ->
            RunLog.ok(
                "收到手机推送：" + file.name + "（" + (size / 1024) + " KB）来自 " + clientIp +
                    "，上传耗时 " + costMs + " ms，开始安装",
            )
            viewModelScope.launch {
                installApk(file.absolutePath, "手机推送（来自 " + clientIp + "）")
            }
        }

        // 下载完成 → 立刻解析 APK 元信息，装之前先告诉用户「这是哪个包、什么版本」
        viewModelScope.launch {
            downloader.state.collect { snapshot ->
                when (snapshot.state) {
                    ApkDownloader.State.DONE -> {
                        val path = snapshot.savedPath
                        _downloadedMeta.value = if (path == null) {
                            null
                        } else {
                            withContext(Dispatchers.IO) {
                                ApkInstaller.readMeta(appContext, File(path))
                            }
                        }
                    }
                    ApkDownloader.State.IDLE -> _downloadedMeta.value = null
                    else -> Unit
                }
            }
        }
    }

    // ==================== 能力自检 ====================

    fun refreshCapabilities() {
        viewModelScope.launch {
            _capabilities.value = withContext(Dispatchers.IO) {
                WifiController.snapshot(appContext)
            }
            // 安装相关的能力跟着一起刷新（从设置页/系统设置返回时状态可能已变）
            _installEnv.value = withContext(Dispatchers.IO) {
                ApkInstaller.env(appContext, settings.value.allowRoot)
            }
            // 推送地址必须实时取：车机每次开机从手机热点拿到的 DHCP 地址都可能不同
            pushServer.refreshNetwork()
        }
    }

    /** 只刷新安装环境自检（安装页进入时用） */
    fun refreshInstallEnv() {
        viewModelScope.launch {
            _installEnv.value = withContext(Dispatchers.IO) {
                ApkInstaller.env(appContext, settings.value.allowRoot)
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
        // 车机触摸屏双击误触很常见：两次点击间隔仅几毫秒时，
        // UI 层的 busy 状态重组还来不及生效，必须在这里原子拦住第二次
        if (!uninstallInFlight.compareAndSet(false, true)) {
            RunLog.w("Uninstall", "忽略重复卸载请求：$packageName（上一次还在执行）")
            return
        }
        viewModelScope.launch {
            try {
                val snapshot = settings.value
                val outcome = withContext(Dispatchers.IO) {
                    AppUninstaller.uninstall(appContext, packageName, snapshot.allowRoot)
                }
                outcome.trace.forEach { RunLog.i("Uninstall", "  $it") }
                if (outcome.success) {
                    RunLog.ok("卸载成功：$packageName（${outcome.strategy}）")
                    // 从启动项移除被卸载的应用，避免开机时等待一个不存在的包
                    repository.removeTarget(packageName)
                    loadApps()
                } else {
                    RunLog.fail("卸载失败：$packageName（${outcome.strategy}）")
                }
                refreshCapabilities()
                onResult(outcome.success, outcome.strategy)
            } finally {
                // 无论成功、失败还是抛异常，都放行下一次卸载
                uninstallInFlight.set(false)
            }
        }
    }

    // ==================== 下载与安装 ====================

    /**
     * 下载一个 APK 文件。
     *
     * 「下载完成 → 一键安装」这条链路刻意留了一步手动确认（确认地址 + 确认安装），
     * 不允许后台偷偷下载安装 —— 车机上装错一个包的代价太大。
     */
    fun startDownload(url: String) {
        if (url.isBlank()) return
        _installMessage.value = null
        _downloadedMeta.value = null
        downloader.start(url)
    }

    fun cancelDownload() = downloader.cancel()

    // ==================== 手机推送（局域网） ====================

    /** 开启推送服务。取不到局域网 IP 或端口全被占用时，状态里会写清原因。 */
    fun startPushServer() {
        _installMessage.value = null
        pushServer.start()
    }

    /** 关闭推送服务：真正关闭 ServerSocket，端口立刻释放 */
    fun stopPushServer() {
        ++diagnosticRevision
        pushServer.stop()
        reportExpiry?.cancel()
        _diagnosticReport.value = null
        _diagnosticReportPublished.value = false
    }

    fun togglePushServer() {
        if (pushState.value.state == PushServer.State.RUNNING ||
            pushState.value.state == PushServer.State.WAITING_NETWORK
        ) stopPushServer() else startPushServer()
    }

    /** 手动刷新一次网络状态（地址、SSID、网关都是实时读的） */
    fun refreshPushNetwork() {
        pushServer.refreshNetwork()
    }

    fun cleanPartialDownloads() {
        val removed = downloader.cleanPartialFiles()
        _installMessage.value = InstallMessage(
            text = if (removed > 0) {
                "已清理 " + removed + " 个未完成的分片文件"
            } else {
                "没有需要清理的分片文件"
            },
            level = MessageLevel.OK,
        )
    }

    /** 扫描本机 / U 盘上的 APK。失败与不可读目录都会如实记进日志。 */
    fun scanLocalApks() {
        if (_isScanningApks.value) return
        viewModelScope.launch {
            _isScanningApks.value = true
            try {
                val result = withContext(Dispatchers.IO) { LocalApkScanner.scan(appContext) }
                result.trace.forEach { RunLog.i("ApkScan", "  " + it) }
                _localApks.value = result.entries
                RunLog.i("ApkScan", "扫描到 " + result.entries.size + " 个 APK 文件")
            } finally {
                _isScanningApks.value = false
            }
        }
    }

    /** 存储权限申请结果（Android 13+ 不再有该权限，拿不到就如实提示） */
    fun onStoragePermissionResult(granted: Boolean) {
        RunLog.i(
            "ApkScan",
            if (granted) "已授予存储读取权限" else "存储读取权限被拒绝，内部存储可能扫不到",
        )
        refreshInstallEnv()
        if (granted) scanLocalApks()
    }

    /**
     * 安装一个 APK 文件（下载产物 / 本机文件 / U 盘文件都走这里）。
     *
     * 结果三态如实呈现：真装上了 / 调起了安装器但用户没确认 / 彻底失败。
     * 无 root 车机上必须由用户在系统安装器里点一次「安装」—— 这是系统硬性要求，
     * 这里不会把它说成「已安装」。
     *
     * @param onFinished 无论成功失败都会回调，供 UI 收尾（例如关掉确认弹窗）
     */
    fun installApk(path: String, sourceNote: String, onFinished: () -> Unit = {}) {
        // 与卸载同理：车机连点间隔只有几毫秒，UI 的 busy 状态来不及拦
        if (!installInFlight.compareAndSet(false, true)) {
            RunLog.w("Install", "忽略重复安装请求：$path（上一次还在执行）")
            return
        }
        viewModelScope.launch {
            _installBusy.value = true
            try {
                RunLog.step("安装请求：" + path + "（" + sourceNote + "）")
                val outcome = withContext(Dispatchers.IO) {
                    ApkInstaller.install(appContext, path, settings.value.allowRoot)
                }
                outcome.trace.forEach { RunLog.i("Install", "  " + it) }

                val label = outcome.meta?.label?.ifBlank { outcome.meta.packageName }
                    ?: File(path).name
                _installMessage.value = when (outcome.state) {
                    InstallState.INSTALLED -> {
                        RunLog.ok(
                            "安装成功：" + label + "（" + outcome.strategy.display + "）来源：" + sourceNote,
                        )
                        // 新装的应用要立刻出现在启动项/管理列表里
                        loadApps()
                        InstallMessage(
                            text = "安装成功：" + label + "（" + outcome.strategy.display + "）",
                            level = MessageLevel.OK,
                        )
                    }
                    InstallState.AWAITING_CONFIRM -> {
                        RunLog.w(
                            "Install",
                            "安装未完成：等待用户在系统安装器里确认（来源：" + sourceNote + "）",
                        )
                        InstallMessage(
                            text = buildString {
                                append("已调起安装器，但还没装完：")
                                if (outcome.needsUnknownSources) {
                                    append("需要先去开启「安装未知应用」权限，再回来点一次安装。")
                                } else {
                                    append("请在车机的系统安装器里点「安装」")
                                    if (outcome.needsAccessibility) append("（无障碍服务未开启，无法代点）")
                                    append("，装完再回来看这里的结果。")
                                }
                            },
                            level = MessageLevel.WARN,
                        )
                    }
                    InstallState.FAILED -> {
                        RunLog.fail("安装失败：" + path + "（来源：" + sourceNote + "）")
                        InstallMessage(
                            text = "安装失败：所有安装方式都没成功，逐级原因见日志页",
                            level = MessageLevel.ERROR,
                        )
                    }
                }
                refreshInstallEnv()
                refreshCapabilities()
            } finally {
                _installBusy.value = false
                installInFlight.set(false)
                onFinished()
            }
        }
    }

    fun dismissInstallMessage() {
        _installMessage.value = null
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

    override fun onCleared() {
        // Activity/ViewModel 结束时真的把监听端口关掉、注销网络回调，
        // 不留下一个「看不见但还在跑」的服务
        downloader.release()
        bleTester.release()
        bleConnectableTester.release()
        bleScanner.release()
        wifiDirect.release()
        pushServer.release()
        super.onCleared()
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
