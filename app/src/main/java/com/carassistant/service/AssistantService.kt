package com.carassistant.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.carassistant.core.BootFlowOrchestrator
import com.carassistant.core.BootReport
import com.carassistant.core.Notifications
import com.carassistant.core.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 执行服务。
 *
 * ## 存在的理由
 *
 * 开机时进程优先级极低，普通后台线程随时可能被 LMK 回收，
 * 结果就是「WiFi 开了一半」或「亿连还没起来」这种半成品状态。
 * 前台服务把优先级提上去，保证流程能完整跑完。
 *
 * 同时借通知栏给用户一个可见的执行进度 —— 车机上没有 adb 的时候，
 * 通知栏是唯一的实时反馈渠道。
 *
 * ## 生命周期
 *
 * 流程跑完立即 `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`，
 * 不常驻。这个 App 的定位是「开机时干一件事就走」，不是常驻后台服务。
 */
class AssistantService : Service() {

    companion object {
        private const val TAG = "AssistantService"

        const val ACTION_RUN_FLOW = "com.carassistant.action.RUN_FLOW"
        const val ACTION_TEST_RUN = "com.carassistant.action.TEST_RUN"
        const val ACTION_STOP = "com.carassistant.action.STOP"
        const val EXTRA_TRIGGER = "extra_trigger"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        RunLog.i(TAG, "服务创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            RunLog.i(TAG, "收到停止指令")
            shutdown()
            return START_NOT_STICKY
        }

        // 必须先转前台：Android 8+ 要求 startForegroundService 后
        // 5 秒内调用 startForeground，否则直接 ANR
        promoteToForeground("正在准备…")

        when (action) {
            ACTION_RUN_FLOW, ACTION_TEST_RUN -> {
                val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
                val force = action == ACTION_TEST_RUN
                startFlow(trigger, force)
            }

            else -> {
                RunLog.w(TAG, "未知 action：$action")
                shutdown()
            }
        }

        // 不重启：开机场景下重启只会造成重复执行
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RunLog.i(TAG, "服务销毁")
        stateJob?.cancel()
        super.onDestroy()
    }

    // ==================== 流程执行 ====================

    private fun startFlow(trigger: String, force: Boolean) {
        RunLog.step("执行服务开始工作（trigger=$trigger, force=$force）")

        // 订阅流程状态，实时刷新通知栏进度
        stateJob = scope.launch {
            BootFlowOrchestrator.state.collectLatest { state ->
                when (state) {
                    is BootFlowOrchestrator.State.Running ->
                        Notifications.updateOngoing(
                            this@AssistantService,
                            state.currentStep,
                        )

                    else -> Unit
                }
            }
        }

        BootFlowOrchestrator.trigger(
            context = applicationContext,
            trigger = trigger,
            force = force,
        ) { report ->
            onFlowFinished(report)
        }
    }

    private fun onFlowFinished(report: BootReport) {
        RunLog.i(TAG, "流程结束，准备收尾")
        RunLog.i(TAG, report.toDisplayText())

        // 结果通知：让用户/开发者一眼看到成败，不用去翻日志
        runCatching {
            val summary = buildString {
                append(if (report.wifiOk) "WiFi ✔" else if (report.wifiRequested) "WiFi ✘" else "WiFi 已跳过")
                if (report.launchResults.isNotEmpty()) {
                    append(" ｜ 应用 ${report.launchOk}成功")
                    if (report.launchFail > 0) append(" ${report.launchFail}失败")
                }
            }
            Notifications.postResult(
                context = this,
                title = "启动流程完成（${report.durationMs}ms）",
                content = summary,
                bigText = report.toDisplayText(),
            )
        }

        shutdown()
    }

    private fun shutdown() {
        stateJob?.cancel()
        stateJob = null
        Notifications.cancelOngoing(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    // ==================== 前台化 ====================

    private fun promoteToForeground(text: String) {
        val notification = Notifications.buildOngoing(this, text)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须显式声明前台服务类型
                startForeground(
                    Notifications.ID_ONGOING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Notifications.ID_ONGOING, notification)
            }
        }.getOrElse { error ->
            // 部分车机 ROM 不支持 specialUse 类型，回退到不带类型的调用
            RunLog.w(TAG, "带类型的前台化失败，尝试回退：${error.message}")
            runCatching { startForeground(Notifications.ID_ONGOING, notification) }
                .onFailure { RunLog.e(TAG, "前台化彻底失败", it) }
        }
    }
}
