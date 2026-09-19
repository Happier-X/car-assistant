package com.carassistant.core

import android.content.Context
import com.carassistant.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动流程编排器。
 *
 * ## 设计原则
 *
 * 1. **幂等**：开机时会收到多个广播（BOOT_COMPLETED、QUICKBOOT_POWERON、
 *    MY_PACKAGE_REPLACED…），必须去重，否则会连续拉起好几次 App。
 *
 * 2. **非阻塞**：整个过程跑在独立协程作用域里。广播接收器的 onReceive
 *    只有 ~10s 窗口，绝不能在里面干活。
 *
 * 3. **容错**：WiFi 失败不阻断后续的 App 启动。用户的核心诉求是
 *    「上车就能看到亿连」，WiFi 只是前置条件，失败了也该继续。
 *
 * 4. **可观测**：每一步都通过 [state] 推给 UI，同时写 RunLog。
 *    车机上出问题没有 logcat 可看，UI 上的实时进度就是唯一的诊断手段。
 */
object BootFlowOrchestrator {

    private const val TAG = "BootFlow"

    /** 去重窗口：这段时间内的重复触发会被忽略 */
    private const val DEDUPLICATION_WINDOW_MS = 20_000L

    /** 流程执行状态，UI 直接 collect 这个 */
    sealed interface State {
        data object Idle : State
        data class Running(val currentStep: String, val completedSteps: List<String>) : State
        data class Finished(val report: BootReport) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 每一步的细粒度事件，供需要逐条展示的场景 */
    private val _events = MutableSharedFlow<FlowEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<FlowEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastRunAt = 0L

    @Volatile
    private var running = false

    /**
     * 触发一次启动流程。
     *
     * @param trigger 触发来源标识（写进日志和报告）
     * @param force 忽略去重窗口。UI 上的「立即测试」按钮用这个。
     * @param onFinished 流程结束回调（Service 用它在结束后自我停止）
     */
    fun trigger(
        context: Context,
        trigger: String,
        force: Boolean = false,
        onFinished: ((BootReport) -> Unit)? = null,
    ) {
        val now = System.currentTimeMillis()

        if (!force) {
            if (running) {
                RunLog.w(TAG, "已有流程在执行，忽略本次触发（$trigger）")
                return
            }
            if (now - lastRunAt < DEDUPLICATION_WINDOW_MS) {
                RunLog.w(
                    TAG,
                    "距上次执行仅 ${now - lastRunAt}ms，忽略重复触发（$trigger）",
                )
                return
            }
        }

        running = true
        lastRunAt = now

        scope.launch {
            val report = execute(context.applicationContext, trigger)
            running = false
            runCatching { onFinished?.invoke(report) }
        }
    }

    // ==================== 主流程 ====================

    private suspend fun execute(context: Context, trigger: String): BootReport {
        val repository = SettingsRepository(context)

        val settings = runCatching { repository.current() }.getOrElse {
            RunLog.e(TAG, "读取配置失败，使用默认值", it)
            SettingsRepository.Settings()
        }

        val startedAt = System.currentTimeMillis()
        val completedSteps = mutableListOf<String>()

        RunLog.step("=========== 启动流程开始（触发源：$trigger）===========")
        updateState(State.Running("准备中", completedSteps))

        var wifiRequested = false
        var wifiOk = false
        var wifiSummary = ""
        val launchResults = mutableListOf<LaunchResult>()

        try {
            // ---------- 阶段 0：等待系统服务就绪 ----------
            if (settings.bootDelayMs > 0 && trigger.contains("BOOT", ignoreCase = true)) {
                val seconds = settings.bootDelayMs / 1000
                advanceState("等待系统就绪（${seconds}s）", completedSteps)
                RunLog.i(TAG, "等待 ${settings.bootDelayMs}ms，让系统服务完成初始化")
                delay(settings.bootDelayMs.toLong())
            }

            // ---------- 阶段 0.5：拉起本应用界面 ----------
            // 开机后先把自己显示出来，用户能实时看到 WiFi/启动项的执行进度；
            // 后面拉起的目标应用（如亿连）会自然覆盖到最上层，两者不冲突。
            // 失败不阻断流程 —— 这只是锦上添花，不是核心任务。
            if (settings.openSelfOnBoot) {
                advanceState("拉起本应用界面…", completedSteps)
                val selfOutcome = AppLauncher.launch(
                    context = context,
                    packageName = context.packageName,
                    allowRoot = settings.allowRoot,
                    verify = false, // 自己刚被服务拉活，前台复查没必要
                )
                if (selfOutcome.success) {
                    RunLog.ok("本应用界面已拉起（${selfOutcome.strategy.display}）")
                    completedSteps += "本应用：已显示"
                } else {
                    RunLog.w(TAG, "拉起本应用界面失败（后台启动限制？），流程继续")
                    completedSteps += "本应用：未能显示（不阻塞）"
                }
            }

            // ---------- 阶段 1：打开 WiFi ----------
            if (settings.wifiEnabled) {
                wifiRequested = true
                advanceState("正在打开 WiFi…", completedSteps)

                val outcome = openWifi(context, settings)

                wifiOk = outcome.first
                wifiSummary = outcome.second

                if (wifiOk) {
                    RunLog.ok("WiFi 阶段完成：$wifiSummary")
                    completedSteps += "WiFi：$wifiSummary"
                } else {
                    RunLog.fail("WiFi 阶段失败：$wifiSummary")
                    completedSteps += "WiFi：失败"
                }
            } else {
                RunLog.i(TAG, "配置中已关闭 WiFi 开关，跳过")
                completedSteps += "WiFi：已跳过"
            }

            // ---------- 阶段 2：拉起目标应用 ----------
            if (settings.launchEnabled) {
                val activeTargets = settings.targets.filter { it.enabled }

                if (activeTargets.isEmpty()) {
                    RunLog.w(TAG, "没有配置任何启动项")
                    completedSteps += "启动项：未配置"
                } else {
                    RunLog.step("--- 开始启动 ${activeTargets.size} 个应用 ---")

                    activeTargets.forEachIndexed { index, target ->
                        val label = target.label.ifBlank {
                            AppLauncher.labelOf(context, target.packageName)
                        }
                        advanceState("正在启动 $label…", completedSteps)

                        // 等待间隔，避免多个 App 同时抢启动导致系统卡顿
                        if (target.waitBeforeMs > 0) delay(target.waitBeforeMs.toLong())

                        val result = launchOne(context, target, settings)
                        launchResults += result

                        if (result.success) {
                            RunLog.ok("${result.label} 启动成功（${result.strategy}）")
                            completedSteps += "${result.label}：已启动"
                        } else {
                            RunLog.fail("${result.label} 启动失败（${result.strategy}）")
                            completedSteps += "${result.label}：失败"
                        }

                        // 非最后一个目标之间留点喘息时间
                        if (index < activeTargets.size - 1) delay(500)
                    }
                }
            } else {
                RunLog.i(TAG, "配置中已关闭应用启动，跳过")
                completedSteps += "启动项：已跳过"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            RunLog.e(TAG, "流程执行异常", t)
            completedSteps += "异常：${t.javaClass.simpleName}"
        }

        val report = BootReport(
            trigger = trigger,
            wifiRequested = wifiRequested,
            wifiOk = wifiOk,
            wifiSummary = wifiSummary,
            launchResults = launchResults,
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
        )

        RunLog.step(
            "=========== 流程结束，耗时 ${report.durationMs}ms，" +
                "成功 ${report.launchOk} / 失败 ${report.launchFail} ==========="
        )

        _events.tryEmit(FlowEvent.Finished(report))
        _state.value = State.Finished(report)
        return report
    }

    /**
     * 打开 WiFi。
     *
     * UI 兜底的细节（拉设置页、让无障碍代点、轮询确认）已下沉到
     * [WifiController.ensureEnabled] 内部，这样编排器和 UI 的手动测试
     * 走的是同一条链路，不会出现两处行为不一致。
     */
    private suspend fun openWifi(
        context: Context,
        settings: SettingsRepository.Settings,
    ): Pair<Boolean, String> {
        val outcome = WifiController.ensureEnabled(
            context = context,
            allowRoot = settings.allowRoot,
            allowUiFallback = settings.wifiUiFallback,
            timeoutMs = settings.wifiVerifyTimeoutMs.toLong(),
        )

        // 失败时把最后一条 trace 带进摘要，用户才知道卡在哪一级
        if (!outcome.success) {
            val reason = outcome.trace.lastOrNull() ?: "未知原因"
            return false to "失败（$reason）"
        }
        return true to outcome.summary
    }

    /**
     * 启动单个应用，失败时自动降级重试。
     *
     * 这里做了一层额外兜底：AppLauncher 内部已经有多级策略，
     * 但它的无障碍策略需要「复查前台」才判定成功；而有些车机 App
     * 冷启动特别慢（10s+），复查会误判失败。所以这里再补一次
     * 无障碍直启 + 宽松判定。
     */
    private suspend fun launchOne(
        context: Context,
        target: LaunchTarget,
        settings: SettingsRepository.Settings,
    ): LaunchResult {
        val label = target.label.ifBlank { AppLauncher.labelOf(context, target.packageName) }

        val outcome = AppLauncher.launch(
            context = context,
            packageName = target.packageName,
            allowRoot = settings.allowRoot,
            verify = settings.verifyLaunch,
        )

        if (outcome.success) {
            return LaunchResult(
                packageName = target.packageName,
                label = label,
                success = true,
                strategy = outcome.strategy.display,
                detail = outcome.trace.lastOrNull().orEmpty(),
            )
        }

        // 最后再补一次无障碍直启，这次不做严格前台复查
        if (AccessibilityBridge.isServiceReady() && AccessibilityBridge.launchPackage(target.packageName)) {
            delay(2000)
            val inForeground = !settings.verifyLaunch ||
                AppLauncher.isInForeground(context, target.packageName)

            if (inForeground) {
                RunLog.ok("$label 通过无障碍补启动成功")
                return LaunchResult(
                    packageName = target.packageName,
                    label = label,
                    success = true,
                    strategy = LaunchStrategy.ACCESSIBILITY.display,
                    detail = "无障碍补启动",
                )
            }
        }

        return LaunchResult(
            packageName = target.packageName,
            label = label,
            success = false,
            strategy = outcome.strategy.display,
            detail = outcome.trace.lastOrNull().orEmpty(),
        )
    }

    // ==================== 辅助 ====================

    private suspend fun advanceState(step: String, completed: List<String>) {
        updateState(State.Running(step, completed.toList()))
        _events.tryEmit(FlowEvent.Step(step))
    }

    private suspend fun updateState(state: State) = withContext(Dispatchers.Main.immediate) {
        _state.value = state
    }

    fun resetState() {
        _state.value = State.Idle
    }
}