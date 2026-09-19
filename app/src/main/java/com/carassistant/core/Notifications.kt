package com.carassistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 通知封装。
 *
 * 通知在这个项目里承担两个职责：
 *  1. **前台服务存活** —— 开机执行期间必须挂着一条常驻通知，
 *     否则 Android 8+ 会直接杀掉服务（进程优先级太低撑不住启动流程）
 *  2. **全屏 Intent 兜底** —— 所有静默启动手段都失败时，
 *     发一条 fullScreenIntent 通知，系统会在锁屏/桌面弹出，用户点一下就打开
 */
object Notifications {

    const val CHANNEL_STATUS = "car_assistant_status"
    const val CHANNEL_ALERT = "car_assistant_alert"

    const val ID_ONGOING = 4001
    const val ID_RESULT = 4002
    const val ID_LAUNCH_FALLBACK = 4100

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        runCatching {
            if (manager.getNotificationChannel(CHANNEL_STATUS) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_STATUS,
                        "执行状态",
                        // LOW：不发声、不振动，车内安静
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) }
                )
            }
            if (manager.getNotificationChannel(CHANNEL_ALERT) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ALERT,
                        "启动提醒",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        setShowBadge(true)
                        // 车机上不要振动和声音，避免干扰驾驶
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
        }
    }

    private fun channelFor(context: Context, high: Boolean): String {
        ensureChannels(context)
        return if (high) CHANNEL_ALERT else CHANNEL_STATUS
    }

    private fun pendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    /** 常驻状态通知（前台服务用） */
    fun buildOngoing(context: Context, text: String): Notification {
        val openApp = Intent(context, com.carassistant.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelFor(context, high = false))
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("车机助手")
            .setContentText(text)
            .setContentIntent(pendingIntent(context, ID_ONGOING, openApp))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setCategory(Notification.CATEGORY_SERVICE)
                    @Suppress("DEPRECATION")
                    setPriority(Notification.PRIORITY_LOW)
                }
            }
            .build()
    }

    fun updateOngoing(context: Context, text: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(ID_ONGOING, buildOngoing(context, text))
        }
    }

    /** 流程结果通知 */
    fun postResult(context: Context, title: String, content: String, bigText: String? = null) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val openApp = Intent(context, com.carassistant.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelFor(context, high = false))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent(context, ID_RESULT, openApp))
                .setAutoCancel(true)

            if (bigText != null) {
                builder.setStyle(Notification.BigTextStyle().bigText(bigText))
            }

            manager.notify(ID_RESULT, builder.build())
        }
    }

    /**
     * 全屏 Intent 兜底通知。
     *
     * 这是最后一道防线：当所有自动启动手段都被系统拦截时，
     * 至少让用户能一键打开目标应用，而不是完全没反应。
     */
    fun postLaunchFallback(
        context: Context,
        packageName: String,
        label: String,
        launchIntent: Intent,
    ) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val contentIntent = pendingIntent(context, ID_LAUNCH_FALLBACK, launchIntent)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelFor(context, high = true))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("无法自动启动 $label")
                .setContentText("点击此处手动打开")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setFullScreenIntent(contentIntent, true)
            }

            manager.notify(ID_LAUNCH_FALLBACK + packageName.hashCode() % 100, builder.build())
        }
    }

    fun cancelOngoing(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(ID_ONGOING)
        }
    }
}