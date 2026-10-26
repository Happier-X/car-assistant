package com.carassistant.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 直链下载器。
 *
 * ## 为什么自己写而不用 DownloadManager / OkHttp
 *
 * - **DownloadManager**：进度只能靠 ContentObserver 轮询、拿不到实时速率，
 *   续传与失败的语义被系统封装掉，出错时几乎没法告诉用户发生了什么。
 *   车机上最需要的恰恰是「到底怎么失败的」。
 * - **OkHttp**：本项目零第三方依赖（release 只有 1.5 MB），为一次流式下载
 *   引入一个近 1 MB 的库不划算。
 *
 * 所以用系统自带的 HttpURLConnection 自己流式写盘。功能一样不少：
 * 进度百分比 + 实时速率、可取消、Content-Length 完整性校验、断点续传、失败可重试。
 *
 * ## 三个刻意的设计
 *
 * 1. **先写 .part 再改名**。下载过程中断电/进程被杀，磁盘上永远不会出现一个
 *    看起来完整、其实残缺的 .apk 被误装。
 * 2. **续传要校验来源 URL**。同名 .part 只有在 URL 相同时才续传，否则会把
 *    两个不同 APK 的字节拼在一起 —— 这种包签名必然校验失败，但报错信息会非常误导人。
 * 3. **不后台偷偷下载**。start() 只由 UI 的确认弹窗触发，URL 原样展示给用户确认。
 */
class ApkDownloader(private val context: Context) {

    private companion object {
        const val TAG = "Download"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }

    enum class State {
        IDLE,
        CONNECTING,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
    }

    /**
     * 下载状态快照。
     *
     * @param totalBytes 服务端未提供长度时为 -1，此时 [percent] 也是 -1，
     *                   UI 必须显示「未知」而不是编一个假进度
     */
    data class Snapshot(
        val url: String = "",
        val state: State = State.IDLE,
        val fileName: String = "",
        val bytesRead: Long = 0L,
        val totalBytes: Long = -1L,
        val bytesPerSecond: Long = 0L,
        val savedPath: String? = null,
        val message: String = "",
        val resumedFrom: Long = 0L,
        val httpCode: Int = 0,
    ) {
        val percent: Int
            get() = if (totalBytes > 0L) {
                ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                -1
            }

        val isRunning: Boolean
            get() = state == State.CONNECTING || state == State.RUNNING

        /** 可以续传：已经落了至少 1 字节，且不是正常完成 */
        val canResume: Boolean
            get() = bytesRead > 0L && state != State.DONE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null

    @Volatile
    private var connection: HttpURLConnection? = null

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** 下载目录：应用专属外部目录下的 apk/（无需存储权限，卸载即清理） */
    fun directory(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "apk/downloads")
    }

    /**
     * 开始下载。
     *
     * 同一个 URL 若已有未完成的 .part，会自动从断点续传。
     */
    fun start(url: String, preferName: String? = null) {
        val target = url.trim()
        if (target.isEmpty()) {
            _state.value = Snapshot(state = State.FAILED, message = "下载地址为空")
            return
        }
        if (job?.isActive == true) {
            RunLog.w(TAG, "已有下载任务在进行，忽略新的下载请求")
            return
        }

        val fileName = resolveFileName(target, preferName)
        _state.value = Snapshot(
            url = target,
            state = State.CONNECTING,
            fileName = fileName,
            message = "正在连接…",
        )
        RunLog.step("开始下载：" + target)
        job = scope.launch { run(target, fileName) }
    }

    /** 取消下载。已写入的 .part 保留，下次同 URL 可续传。 */
    fun cancel() {
        val wasActive = job?.isActive == true
        job?.cancel()
        // HttpURLConnection 的 read() 是阻塞的，协程取消打断不了它，
        // 必须断开连接让 read 抛异常返回
        runCatching { connection?.disconnect() }
        if (wasActive) {
            _state.value = _state.value.copy(
                state = State.CANCELLED,
                message = "已取消（已下载部分保留，可继续下载）",
            )
            RunLog.w(TAG, "下载已取消")
        }
    }

    fun release() {
        cancel()
        scope.cancel()
    }

    /** 重置为初始态（清掉上次的失败/取消提示） */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = Snapshot()
    }

    /** 清理下载目录里所有未完成的分片文件，返回清理个数 */
    fun cleanPartialFiles(): Int {
        if (job?.isActive == true) return 0
        var removed = 0
        runCatching {
            directory().listFiles()?.forEach { file ->
                if (file.name.endsWith(".part") || file.name.endsWith(".part.meta")) {
                    if (file.delete()) removed++
                }
            }
        }
        if (removed > 0) RunLog.i(TAG, "已清理 " + removed + " 个未完成的分片文件")
        return removed
    }

    // ==================== 实际下载 ====================

    private suspend fun run(url: String, fileName: String) {
        val dir = directory().apply { mkdirs() }
        val target = File(dir, fileName)
        val part = File(dir, fileName + ".part")
        val metaFile = File(dir, fileName + ".part.meta")

        // 续传判断：.part 存在 **且** 记录里的来源 URL 与本次一致
        val recordedUrl = runCatching {
            if (metaFile.exists()) metaFile.readText().trim() else null
        }.getOrNull()
        var resumeFrom = if (part.exists() && recordedUrl == url) part.length() else 0L
        if (resumeFrom > 0L) {
            RunLog.i(TAG, "发现未完成的分片，尝试从 " + resumeFrom + " 字节续传")
            _state.value = _state.value.copy(
                resumedFrom = resumeFrom,
                bytesRead = resumeFrom,
                message = "从断点续传…",
            )
        } else if (part.exists()) {
            RunLog.w(TAG, "同名分片来自其它地址，已丢弃")
            part.delete()
            resumeFrom = 0L
        }
        runCatching { metaFile.writeText(url) }

        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(url, resumeFrom) ?: throw IllegalStateException("不支持的协议：" + url)
            connection = conn
            var code = conn.responseCode

            if (resumeFrom > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                // 服务端不支持 Range：只能从头来，否则文件会错位
                RunLog.w(TAG, "服务端不支持断点续传（HTTP " + code + "），改为完整重新下载")
                part.delete()
                resumeFrom = 0L
                runCatching { conn.disconnect() }
                conn = openConnection(url, 0L) ?: throw IllegalStateException("不支持的协议：" + url)
                connection = conn
                code = conn.responseCode
            }

            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val range = conn.getHeaderField("Content-Range").orEmpty()
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(range)
                require(match != null && match.groupValues[1].toLong() == resumeFrom) {
                    "服务器返回的 Content-Range 与续传位置不符，拒绝拼接文件"
                }
            }
            val declaredLength = conn.contentLengthLong
            RunLog.i(
                TAG,
                "HTTP " + code + "，Content-Length=" + declaredLength +
                    if (resumeFrom > 0L) "（续传自 " + resumeFrom + " 字节）" else "",
            )
            if (code !in 200..299) {
                fail(
                    part = part,
                    message = "服务器返回 HTTP " + code + describeHttpError(code),
                    httpCode = code,
                )
                return
            }

            val total = if (declaredLength > 0L) declaredLength + resumeFrom else -1L
            var written = resumeFrom
            var speed = 0L
            var lastTick = System.currentTimeMillis()
            var lastBytes = written
            val startedAt = System.currentTimeMillis()

            _state.value = _state.value.copy(
                state = State.RUNNING,
                totalBytes = total,
                bytesRead = written,
                httpCode = code,
                message = "下载中…",
            )

            RandomAccessFile(part, "rw").use { out ->
                out.setLength(resumeFrom)
                out.seek(resumeFrom)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (!currentCoroutineContext().isActive) {
                            throw CancellationException("下载已取消")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTick
                        if (elapsed >= PROGRESS_INTERVAL_MS) {
                            speed = ((written - lastBytes) * 1000L) / elapsed.coerceAtLeast(1L)
                            lastBytes = written
                            lastTick = now
                            _state.value = _state.value.copy(
                                bytesRead = written,
                                bytesPerSecond = speed,
                            )
                        }
                    }
                }
            }

            // ---------- 完整性校验 ----------
            if (written <= 0L) {
                fail(part, "下载内容为空（0 字节）", code)
                return
            }
            if (total > 0L && written != total) {
                // 保留 .part：下次点「继续下载」可以补齐缺口
                fail(
                    part,
                    "下载不完整：" + written + " / " + total + " 字节（已保留分片，可继续下载）",
                    code,
                )
                return
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                fail(part, "文件重命名失败（存储空间不足或权限异常）", code)
                return
            }
            metaFile.delete()

            val totalElapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val averageSpeed = if (speed > 0L) speed else (written * 1000L) / totalElapsed
            _state.value = Snapshot(
                url = url,
                state = State.DONE,
                fileName = fileName,
                bytesRead = written,
                totalBytes = if (total > 0L) total else written,
                bytesPerSecond = averageSpeed,
                savedPath = target.absolutePath,
                message = if (total > 0L) "下载完成" else "下载完成（服务端未提供长度，无法校验完整性）",
                httpCode = code,
            )
            RunLog.ok(
                "下载完成：" + target.name + "（" + (written / 1024) + " KB）→ " +
                    target.absolutePath,
            )
        } catch (cancelled: CancellationException) {
            RunLog.w(TAG, "下载任务被取消：" + cancelled.message)
            // 状态已由 cancel() 设置，这里不覆盖
        } catch (t: Throwable) {
            if (!currentCoroutineContext().isActive) {
                RunLog.w(TAG, "下载被中断：" + t.javaClass.simpleName)
            } else {
                fail(
                    part = part,
                    message = "下载异常：" + t.javaClass.simpleName + " " + (t.message ?: "") +
                        "（分片已保留，可重试）",
                    httpCode = 0,
                )
            }
        } finally {
            runCatching { conn?.disconnect() }
            connection = null
        }
    }

    private fun openConnection(url: String, resumeFrom: Long): HttpURLConnection? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        // 明确要求不压缩：否则收到的字节数和 Content-Length 对不上，完整性校验失效
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "CarAssistant/1.3")
        if (resumeFrom > 0L) conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-")
        conn.connect()
        return conn
    }

    private fun fail(part: File, message: String, httpCode: Int) {
        _state.value = _state.value.copy(
            state = State.FAILED,
            message = message,
            bytesRead = if (part.exists()) part.length() else _state.value.bytesRead,
            httpCode = httpCode,
        )
        RunLog.fail("下载失败：" + message)
    }

    private fun describeHttpError(code: Int): String = when (code) {
        403 -> "（服务器拒绝访问，直链可能需要带 token）"
        404 -> "（地址不存在，检查链接是否写错）"
        in 500..599 -> "（服务器内部错误）"
        else -> ""
    }

    /**
     * 从 URL 推断文件名。
     *
     * 车机上用户输入的链接常常带一长串查询参数，所以要去掉 ? 之后的部分；
     * 同时过滤路径分隔符等非法字符 —— 否则写盘会直接抛异常。
     */
    private fun resolveFileName(url: String, preferName: String?): String {
        val fromUrl = runCatching { URL(url).path.substringAfterLast('/') }.getOrDefault("")
        val raw = preferName?.takeIf { it.isNotBlank() } ?: fromUrl
        val illegal = Regex("[\\\\/:*?\"<>|\\s]")
        val cleaned = raw.substringBefore('?').replace(illegal, "_").trim()
        val withExt = when {
            cleaned.isEmpty() -> "download.apk"
            cleaned.endsWith(".apk", ignoreCase = true) -> cleaned
            else -> cleaned + ".apk"
        }
        return if (withExt.length > 80) withExt.takeLast(80) else withExt
    }
}
