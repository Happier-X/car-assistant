package com.carassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.carassistant.core.RunLog
import com.carassistant.data.SettingsRepository
import com.carassistant.service.AssistantService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 触发器接收器。
 *
 * ## 职责边界
 *
 * 只做两件事：
 *  1. 判断这个事件是否应该触发流程（读配置）
 *  2. 把执行转交给前台服务
 *
 * **绝不在 onReceive 里干活**。开机广播的 onReceive 只有约 10 秒窗口，
 * 而且此时进程优先级极低，一旦返回就可能被 LMK 回收 —— 这正是很多人
 * 「开机自启偶尔失灵」的根因。转成前台服务才能拿到足够的执行时间和优先级。
 *
 * ## 为什么监听这么多广播
 *
 * 车机的开机流程五花八门：有的是标准 BOOT_COMPLETED，有的是
 * QUICKBOOT_POWERON（快速启动，熄火不断电的车机常见），
 * 有的是熄火后再上电只发 POWER_CONNECTED。全监听才能覆盖真实场景，
 * 去重交给 [com.carassistant.core.BootFlowOrchestrator] 处理。
 */
class TriggerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Trigger"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext

        // pendingResult 让接收器在异步读配置期间保持存活
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                handleTrigger(appContext, intent, action)
            } catch (t: Throwable) {
                RunLog.e(TAG, "处理触发事件失败", t)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun handleTrigger(context: Context, intent: Intent, action: String) {
        RunLog.i(TAG, "收到广播：$action")

        val settings = runCatching { SettingsRepository(context).current() }.getOrElse {
            RunLog.e(TAG, "读取配置失败", it)
            return
        }

        val triggerName = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> {
                if (!settings.triggerOnBoot) return logSkip(action, "开机自启已关闭")
                "BOOT"
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 覆盖安装后系统不会自动启动我们，主动激活一次，
                // 这样「装完新版本立刻重启车机」的验证流程更顺
                if (!settings.triggerOnBoot) return logSkip(action, "开机自启已关闭")
                "PACKAGE_REPLACED"
            }

            Intent.ACTION_POWER_CONNECTED -> {
                if (!settings.triggerOnPowerConnect) return logSkip(action, "上电触发已关闭")
                "POWER_CONNECTED"
            }

            Intent.ACTION_SCREEN_ON -> {
                if (!settings.triggerOnScreenOn) return logSkip(action, "亮屏触发已关闭")
                "SCREEN_ON"
            }

            Intent.ACTION_DOCK_EVENT -> {
                val dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, -1)
                if (dockState != Intent.EXTRA_DOCK_STATE_CAR) {
                    return logSkip(action, "非车载底座事件（state=$dockState）")
                }
                if (!settings.triggerOnPowerConnect) return logSkip(action, "上电触发已关闭")
                "DOCK_CAR"
            }

            else -> return logSkip(action, "未处理的动作")
        }

        RunLog.step("触发条件满足，启动执行服务（来源：$triggerName）")
        startExecutionService(context, triggerName)
    }

    private fun startExecutionService(context: Context, triggerName: String) {
        val serviceIntent = Intent(context, AssistantService::class.java).apply {
            action = AssistantService.ACTION_RUN_FLOW
            putExtra(AssistantService.EXTRA_TRIGGER, triggerName)
        }

        runCatching {
            // Android 8+ 必须用 startForegroundService，且服务需在 5s 内
            // 调用 startForeground，否则 ANR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            RunLog.i(TAG, "执行服务已启动")
        }.getOrElse {
            // 部分车机 ROM 在开机阶段禁止启动前台服务（后台启动限制），
            // 退而求其次：直接在进程内跑流程
            RunLog.w(TAG, "启动前台服务失败，改为进程内直接执行：${it.message}")
            com.carassistant.core.BootFlowOrchestrator.trigger(
                context = context,
                trigger = triggerName,
                force = true,
            )
        }
    }

    private fun logSkip(action: String, reason: String) {
        RunLog.i(TAG, "忽略 $action：$reason")
    }
}