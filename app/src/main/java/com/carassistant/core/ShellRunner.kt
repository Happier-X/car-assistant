package com.carassistant.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shell 执行器（Kotlin 版）。
 *
 * 两条通道：
 *  - [exec]      以 App 自身 uid 运行，无需任何权限
 *  - [execRoot]  走 su，仅当设备已 root
 *
 * 关键设计：**永不阻塞调用线程**。全部是 suspend 函数，在 IO 线程池上跑，
 * 并且每个命令都有硬超时 —— 车机上 su 弹出授权框时如果没有超时，
 * 整个开机流程会永久卡死。
 */
object ShellRunner {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0

        /** 单行摘要，用于日志 */
        fun brief(maxLen: Int = 200): String {
            val text = stdout.trim().ifEmpty { stderr.trim() }
            val clipped = if (text.length > maxLen) text.take(maxLen) + "…" else text
            return "exit=$exitCode" + if (clipped.isEmpty()) "" else " $clipped"
        }

        override fun toString(): String = brief()
    }

    /**
     * root 状态。用密封类表达「未探测/可用/不可用」，
     * 避免用可空 Boolean 导致语义模糊。
     */
    sealed interface RootStatus {
        data object Unknown : RootStatus
        data class Available(val suPrefix: List<String>) : RootStatus
        data class Unavailable(val reason: String) : RootStatus
    }

    @Volatile
    private var rootStatus: RootStatus = RootStatus.Unknown

    /** 不同车机的 su 位置和参数完全不同，逐个探测 */
    private val suCandidates: List<List<String>> = listOf(
        listOf("su", "-c"),
        listOf("su", "0"),
        listOf("su", "root", "-c"),
        listOf("/system/bin/su", "-c"),
        listOf("/system/xbin/su", "-c"),
        listOf("/sbin/su", "-c"),
        listOf("/su/bin/su", "-c"),
        listOf("/system/sbin/su", "-c"),
    )

    /** 探测 root。结果缓存，可用 [resetRootCache] 强制重测。 */
    suspend fun detectRoot(force: Boolean = false): RootStatus {
        if (!force) {
            rootStatus.let { if (it !is RootStatus.Unknown) return it }
        }
        return withContext(Dispatchers.IO) {
            for (prefix in suCandidates) {
                val result = runCatching {
                    execInternal(prefix + "id", timeoutMs = 2500)
                }.getOrNull() ?: continue

                if (result.stdout.contains("uid=0")) {
                    RunLog.ok("检测到 root：${prefix.joinToString(" ")}")
                    return@withContext RootStatus.Available(prefix).also { rootStatus = it }
                }
            }
            RunLog.i("Shell", "未检测到可用的 root")
            RootStatus.Unavailable("所有 su 路径均不可用").also { rootStatus = it }
        }
    }

    fun resetRootCache() {
        rootStatus = RootStatus.Unknown
    }

    fun cachedRootStatus(): RootStatus = rootStatus

    /** 以 root 执行一条 shell 命令串。无 root 时返回 null。 */
    suspend fun execRoot(command: String, timeoutMs: Long = 5000): Result? {
        val status = detectRoot()
        if (status !is RootStatus.Available) return null
        val argv = status.suPrefix + command
        return runCatching { execInternal(argv, timeoutMs) }.getOrNull()
    }

    /** 以 App 自身身份执行 */
    suspend fun exec(vararg command: String, timeoutMs: Long = 5000): Result =
        withContext(Dispatchers.IO) {
            runCatching { execInternal(command.toList(), timeoutMs) }
                .getOrElse { Result(-1, "", it.message ?: "unknown") }
        }

    private suspend fun execInternal(
        argv: List<String>,
        timeoutMs: Long,
    ): Result = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(argv)
            .redirectErrorStream(false)
            .start()

        // 必须并发读取 stdout/stderr，否则管道缓冲区满会导致进程挂住
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = Thread { process.inputStream.drainInto(stdout) }.apply { isDaemon = true; start() }
        val errReader = Thread { process.errorStream.drainInto(stderr) }.apply { isDaemon = true; start() }

        try {
            withTimeout(timeoutMs) {
                process.waitFor()
            }
            outReader.join(500)
            errReader.join(500)
            Result(process.exitValue(), stdout.toString(), stderr.toString())
        } catch (_: TimeoutCancellationException) {
            process.destroy()
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            RunLog.w("Shell", "命令超时(${timeoutMs}ms): ${argv.joinToString(" ")}")
            Result(-1, stdout.toString(), "TIMEOUT")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun java.io.InputStream.drainInto(sink: StringBuilder) {
        runCatching {
            BufferedReader(InputStreamReader(this, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (sink.length < 64 * 1024) sink.append(line).append('\n')
                }
            }
        }
    }

    /**
     * 从 `dumpsys activity activities` 里解析当前前台包名。
     * 这是判断「App 是否真的被拉起来了」最可靠的方式（需 root）。
     */
    suspend fun foregroundPackageViaRoot(): String? {
        val result = execRoot(
            "dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity'",
            timeoutMs = 4000,
        ) ?: return null

        val line = result.stdout.lineSequence().firstOrNull { it.contains('/') } ?: return null
        // 形如: mResumedActivity: ActivityRecord{abc u0 com.example/.MainActivity t123}
        return line.substringBefore('/')
            .substringAfterLast(' ')
            .trim()
            .removeSurrounding("{", "}")
            .takeIf { it.contains('.') }
    }
}