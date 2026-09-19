package com.carassistant

import android.app.Application
import com.carassistant.core.Notifications
import com.carassistant.core.RunLog
import java.io.File

/**
 * Application 入口。
 *
 * 这里只做「不管从哪个入口启动都必需」的初始化：
 *  - 日志系统（越早越好，后面所有组件都依赖它）
 *  - 通知渠道（Android 8+ 必须在使用前创建）
 *
 * 刻意不做任何耗时操作 —— 本 App 会被开机广播唤起，
 * Application.onCreate 拖慢了会直接影响能否在系统窗口期内完成启动。
 */
class CarAssistantApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        RunLog.attach(File(filesDir, "car_assistant.log"))
        RunLog.i(TAG, "应用启动（进程 ${android.os.Process.myPid()}）")

        Notifications.ensureChannels(this)
    }

    companion object {
        private const val TAG = "App"

        @Volatile
        private lateinit var instance: CarAssistantApp

        fun get(): CarAssistantApp = instance
    }
}