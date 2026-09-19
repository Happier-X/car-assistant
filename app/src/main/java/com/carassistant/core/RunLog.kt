package com.carassistant.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志。
 *
 * 车机上调试很麻烦（不方便总连着 adb），所以日志同时：
 *  - 进内存环形缓冲 → UI 里实时滚动展示
 *  - 落盘 → 出问题后可以回看历史
 *  - 转发给 logcat → 需要 adb 时仍可用
 *
 * 全流程用 SharedFlow 推给 Compose，UI 侧 collectAsState 即可。
 */
object RunLog {

    private const val TAG = "CarAssistant"
    private const val MAX_MEMORY_LINES = 500
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 供 UI 做「新日志到达」动画/自动滚动用 */
    private val _newLine = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val newLine: SharedFlow<String> = _newLine.asSharedFlow()

    @Volatile
    private var logFile: File? = null

    fun attach(file: File) {
        logFile = file
        file.parentFile?.mkdirs()
        // 超过上限就滚动一次，避免长期运行吃满空间
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            val backup = File(file.absolutePath + ".old")
            backup.delete()
            file.renameTo(backup)
        }
    }

    fun i(tag: String, message: String) = write("I", tag, message)

    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        write("E", tag, throwable?.let { "$message: ${it.javaClass.simpleName} ${it.message}" } ?: message)

    /** 流程里程碑，UI 上会高亮 */
    fun step(message: String) = write("▶", "STEP", message)

    fun ok(message: String) = write("✔", "OK", message)

    fun fail(message: String) = write("✘", "FAIL", message)

    private fun write(level: String, tag: String, message: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $message"

        _lines.value = (_lines.value + line).takeLast(MAX_MEMORY_LINES)
        _newLine.tryEmit(line)

        when (level) {
            "E" -> Log.e(TAG, "[$tag] $message")
            "W" -> Log.w(TAG, "[$tag] $message")
            else -> Log.i(TAG, "[$tag] $message")
        }

        runCatching {
            logFile?.appendText(line + "\n")
        }
    }

    fun clear() {
        _lines.value = emptyList()
        logFile?.delete()
    }

    fun dump(): String = _lines.value.joinToString("\n")
}