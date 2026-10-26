package com.carassistant.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机推送 APK 的极简 HTTP 服务（车机端）。
 *
 * ## 场景：车机连手机热点，手机浏览器把 APK 推上来
 *
 * 车机的网络来源是「手机热点」，所以手机与车机天然在同一个网段
 * （手机通常是网关，但热点隔离或防火墙可能阻止访问，需真机验证）。
 * **手机端不需要安装任何 App**，打开浏览器就能传 —— 这是本方案的核心。
 *
 * ## 为什么手写 ServerSocket
 *
 * 零第三方依赖是硬约束（release 只有 1.5 MB，不引入 NanoHTTPD 之类）。
 * 而这里只有「一个静态上传页 + 一个流式上传接口」，手写反而能把失败原因
 * （缺 Content-Length、上传中断、落盘失败、端口被占用）说清楚。
 *
 * ## 网络地址是「实时读」的，不是启动时缓存
 *
 * 车机每次开机从手机热点拿到的 DHCP 地址都可能变化，因此：
 * - 地址、SSID、网关每次展示前都从 ConnectivityManager / WifiManager 现取
 * - 监听 socket 绑在 0.0.0.0（本机所有网卡），换网/换 IP 不需要重启服务
 * - 注册 NetworkCallback：IP 变化、网络恢复时自动刷新展示地址
 * - 如果用户点了开启但还没连上热点，进入 [State.WAITING_NETWORK]，
 *   连上拿到 IP 后自动开始监听，不报错、也不静默失败
 *
 * ## 安全边界（刻意的取舍）
 *
 * - 只服务局域网客户端：每个请求都会校验来源 IP 是 site-local / link-local /
 *   loopback，其它来源直接 403
 * - 固定路由：GET /（上传页）、GET /health、POST|PUT /upload，以及只读 GET /diagnostics 和 /diagnostics.txt
 * - 目标路径固定：应用专属目录下的 apk/，客户端**不能**传路径参数；
 *   文件名清洗（剥目录、清 ..、限制字符）后强制 .apk 后缀，杜绝路径穿越
 * - 并发上限 4、单文件上限 512 MB，超出直接 503 / 413
 *
 * ## 上传失败的行为
 *
 * 浏览器不支持续传，所以中断时删除未完成分片，并如实报告「已收到 x / y 字节」。
 * 磁盘上永远不会留下一个看起来完整、实际残缺的 .apk 被误装。
 */
class PushServer(private val context: Context) {
    private val diagnosticReports = DiagnosticReportStore()
    private var diagnosticRevision = 0L

    fun clearDiagnosticReport(): Long = synchronized(lock) {
        diagnosticReports.clear()
        ++diagnosticRevision
    }

    fun publishDiagnosticReport(text: String, revision: Long): Boolean = synchronized(lock) {
        if (revision == diagnosticRevision && desired && _state.value.running) {
            diagnosticReports.publish(text)
            true
        } else false
    }

    private companion object {
        const val TAG = "Push"
        const val DEFAULT_PORT = 8080
        const val MAX_PORT_TRIES = 10
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_CONCURRENT_REQUESTS = 4
        const val SOCKET_READ_TIMEOUT_MS = 20_000
        const val READ_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 300L
        const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024
        const val BACKLOG = 8
    }

    enum class State {
        /** 用户关掉了推送 */
        STOPPED,

        /** 用户已开启，但车机还没拿到局域网 IP（例如还没连上手机热点） */
        WAITING_NETWORK,

        /** 正在监听，可以上传 */
        RUNNING,

        /** 真的起不来（端口全被占用等） */
        FAILED,
    }

    /**
     * 当前网络状况（每次展示都重新读，不做缓存）。
     *
     * @param ssid 当前连接的 WiFi 名称；系统限制读不到时为空字符串
     * @param ipAddress 当前活动网络的局域网 IPv4；没有网络时为 null
     * @param gateway 默认网关（手机热点的网关通常就是手机自己）
     */
    data class LanStatus(
        val wifiEnabled: Boolean = false,
        val connectedViaWifi: Boolean = false,
        val ssid: String = "",
        val ipAddress: String? = null,
        val gateway: String? = null,
    )

    /**
     * 推送服务状态。
     *
     * @param urls 车机端展示给用户的访问地址（按当前 IP 实时拼出）
     * @param expectedBytes 本次上传的 Content-Length，未知为 -1
     */
    data class Snapshot(
        val state: State = State.STOPPED,
        val urls: List<String> = emptyList(),
        val port: Int = 0,
        val message: String = "",
        val lastFileName: String = "",
        val lastSizeBytes: Long = 0L,
        val lastClientIp: String = "",
        val lastCostMs: Long = 0L,
        val receiving: Boolean = false,
        val receivedBytes: Long = 0L,
        val expectedBytes: Long = -1L,
        val lan: LanStatus = LanStatus(),
    ) {
        val running: Boolean get() = state == State.RUNNING

        /** UI 上是否需要提示「WiFi 已开但未连上热点」 */
        val waitingForHotspot: Boolean
            get() = state == State.WAITING_NETWORK ||
                (state == State.RUNNING && lan.ipAddress == null)

        /** -1 表示未知（没有 Content-Length 或还没开始） */
        val percent: Int
            get() = if (expectedBytes > 0L) {
                ((receivedBytes * 100L) / expectedBytes).toInt().coerceIn(0, 100)
            } else {
                -1
            }

        /** 主地址，UI 上要大字展示并支持复制 */
        val primaryUrl: String get() = urls.firstOrNull().orEmpty()
    }

    /**
     * 上传完成后的回调：落盘文件、字节数、来源 IP、耗时。
     *
     * 用回调而不是直接依赖 ViewModel，是为了让 core 包保持与 UI 无关。
     */
    @Volatile
    var onUploaded: ((File, Long, String, Long) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** 用户是否希望推送服务开着（用于「等到有 IP 就自动启动」） */
    @Volatile
    private var desired = false

    private val lock = Any()
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val activeRequests = AtomicInteger(0)
    private val activeUploads = AtomicInteger(0)

    private val connectivity: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * 网络变化回调。
     *
     * 只做一件事：重新读一次网络状态并刷新展示地址。
     * 监听 socket 绑在 0.0.0.0，所以换 IP 不需要重建。
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()

        override fun onLost(network: Network) = refreshNetwork()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = refreshNetwork()

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) = refreshNetwork()
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    init {
        // 先读一次，让 UI 一进页面就能看到 SSID / IP / 是否联网
        refreshNetwork()
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                networkCallback,
            )
        }.onFailure {
            RunLog.w(TAG, "注册网络回调失败：" + it.javaClass.simpleName + " " + (it.message ?: ""))
        }
    }

    // ==================== 网络状态 ====================

    /**
     * 重新读取当前网络状态并刷新对外展示的地址。
     *
     * 这是「实时取地址」的唯一入口：NetworkCallback、UI 进入页面、手动刷新都走它。
     */
    fun refreshNetwork() {
        val lan = readLanStatus()
        synchronized(lock) {
            val current = _state.value
            if (!desired) {
                _state.value = current.copy(lan = lan)
                return
            }

            when {
                lan.ipAddress == null -> {
                    // 没有局域网地址：
                    // - 已经在监听（socket 绑在 0.0.0.0）→ 保持 RUNNING，只清空展示地址，
                    //   网络恢复后同一个端口立刻可用，不需要重绑
                    // - 还没监听 → 进入等待态，拿到 IP 后自动 bind
                    _state.value = current.copy(
                        state = if (serverSocket != null) State.RUNNING else State.WAITING_NETWORK,
                        lan = lan,
                        urls = emptyList(),
                        message = waitingMessage(lan),
                    )
                }

                current.state == State.RUNNING && serverSocket != null -> {
                    // 服务已在监听（0.0.0.0），只需要把展示地址换成新 IP
                    _state.value = current.copy(
                        lan = lan,
                        urls = urlFor(lan.ipAddress, current.port),
                        message = runningMessage(lan),
                    )
                }

                else -> {
                    bindAndRun(lan)
                }
            }
        }
    }

    private fun waitingMessage(lan: LanStatus): String = when {
        !lan.wifiEnabled ->
            "WiFi 未开启，暂无推送地址：请在车机 WiFi 设置里打开 WiFi 并连接手机热点"
        else ->
            "WiFi 已开但未连接手机热点，暂无推送地址；连上热点拿到 IP 后会自动就绪"
    }

    private fun runningMessage(lan: LanStatus): String = buildString {
        append("监听中：手机连同一个热点，用浏览器打开上面的地址即可上传 APK")
        if (lan.ssid.isNotBlank()) append("（当前 WiFi：" + lan.ssid + "）")
    }

    private fun urlFor(ip: String?, port: Int): List<String> =
        if (ip == null || port <= 0) emptyList() else listOf("http://" + ip + ":" + port)

    // ==================== 开关 ====================

    /**
     * 启动服务。
     *
     * 没有 IP 时进入 [State.WAITING_NETWORK] 并记住「用户想开」，
     * 网络恢复后由 NetworkCallback 自动绑定端口。返回最新快照供 UI 使用。
     */
    fun start(): Snapshot {
        desired = true
        refreshNetwork()
        return _state.value
    }

    /** 停止服务：真正关闭监听端口，而不是只改个标志位 */
    fun stop() = synchronized(lock) {
        clearDiagnosticReport()
        desired = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        // 正在传输的连接也要断开，否则 upload 会一直挂着
        synchronized(clients) {
            clients.toList().forEach { socket -> runCatching { socket.close() } }
            clients.clear()
        }
        if (_state.value.state != State.STOPPED) {
            _state.value = _state.value.copy(
                state = State.STOPPED,
                urls = emptyList(),
                port = 0,
                message = "推送服务已停止，端口已释放",
                receiving = false,
            )
            RunLog.w(TAG, "手机推送服务已停止，端口已释放")
        }
    }

    /** ViewModel 结束时调用：停服 + 注销网络回调 */
    fun release() {
        stop()
        onUploaded = null
        scope.cancel()
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    private fun bindAndRun(lan: LanStatus) {
        // 已经有监听 socket 时绝不重复绑定（否则会漏掉旧 socket、多占一个端口）
        if (serverSocket != null) return

        val dir = directory().apply { mkdirs() }
        var bound: ServerSocket? = null
        var boundPort = 0
        var lastError = ""
        for (candidate in DEFAULT_PORT until DEFAULT_PORT + MAX_PORT_TRIES) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                // 绑 0.0.0.0：本机所有网卡都能收，换 IP 不用重启服务；
                // 安全边界靠 handle() 里的「只服务局域网客户端」校验。
                socket.bind(InetSocketAddress("0.0.0.0", candidate), BACKLOG)
                bound = socket
                boundPort = candidate
                break
            } catch (t: Throwable) {
                runCatching { socket.close() }
                lastError = t.javaClass.simpleName + " " + (t.message ?: "")
                RunLog.w(TAG, "端口 " + candidate + " 绑定失败：" + lastError)
            }
        }

        if (bound == null) {
            _state.value = _state.value.copy(
                state = State.FAILED,
                port = 0,
                urls = emptyList(),
                lan = lan,
                message = "推送不可用：" + DEFAULT_PORT + "~" +
                    (DEFAULT_PORT + MAX_PORT_TRIES - 1) + " 端口都被占用或无法绑定（" +
                    lastError + "）",
            )
            RunLog.fail(_state.value.message)
            return
        }

        val server: ServerSocket = bound
        serverSocket = server
        _state.value = _state.value.copy(
            state = State.RUNNING,
            port = boundPort,
            urls = urlFor(lan.ipAddress, boundPort),
            lan = lan,
            message = runningMessage(lan),
        )
        RunLog.ok(
            "手机推送服务已启动：" + _state.value.primaryUrl + "（WiFi " +
                (if (lan.ssid.isBlank()) "SSID 不可见" else lan.ssid) +
                "，IP " + (lan.ipAddress ?: "?") + "，网关 " + (lan.gateway ?: "?") +
                "，上传目录 " + dir.absolutePath + "）",
        )
        acceptJob = scope.launch { acceptLoop(server) }
    }

    /** 接收目录，与直链下载共用：应用专属目录下的 apk/ */
    fun directory(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "apk")
    }

    // ==================== 接受连接 ====================

    private suspend fun acceptLoop(server: ServerSocket) {
        while (currentCoroutineContext().isActive) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                if (currentCoroutineContext().isActive) {
                    RunLog.e(TAG, "accept 失败，推送服务退出", t)
                }
                break
            }
            synchronized(lock) {
                if (!desired || serverSocket !== server) {
                    runCatching { socket.close() }
                } else {
                    clients.add(socket)
                    scope.launch { handle(socket) }
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            if (activeRequests.incrementAndGet() > MAX_CONCURRENT_REQUESTS) {
                writeAll(socket, simpleResponse(503, "服务繁忙，请稍后再试"))
                return@withContext
            }
            socket.soTimeout = SOCKET_READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val clientIp = socket.inetAddress?.hostAddress ?: "未知"

            // 只服务局域网内的客户端：外网/其它来源直接拒绝
            if (!isLanClient(socket.inetAddress)) {
                RunLog.w(TAG, "拒绝非局域网来源的请求：" + clientIp)
                writeAll(socket, simpleResponse(403, "only LAN clients are allowed"))
                return@withContext
            }

            val header = readHeaderBlock(input)
            if (header == null) {
                writeAll(socket, simpleResponse(400, "请求头不完整"))
                return@withContext
            }
            if (header.lineSequence().any { it.startsWith("Transfer-Encoding:", ignoreCase = true) } ||
                header.lineSequence().count { it.startsWith("Content-Length:", ignoreCase = true) } > 1
            ) {
                writeAll(socket, simpleResponse(400, "不支持分块上传或重复 Content-Length"))
                return@withContext
            }
            val request = parseRequest(header)
            val respond: (ByteArray) -> Unit = { bytes ->
                output.write(bytes)
                output.flush()
            }

            when {
                request.method == "GET" && (request.path == "/diagnostics" || request.path == "/diagnostics.txt") -> {
                    respond(diagnosticResponse(request.path))
                }

                request.method == "GET" && (request.path == "/" || request.path == "/index.html") ->
                    respond(response(200, "text/html; charset=utf-8", uploadPage().toByteArray(Charsets.UTF_8)))

                request.method == "GET" && request.path == "/health" ->
                    respond(response(200, "text/plain; charset=utf-8", "ok".toByteArray()))

                (request.method == "POST" || request.method == "PUT") && request.path == "/upload" ->
                    receiveUpload(input, respond, request, clientIp)

                else ->
                    respond(simpleResponse(404, "只支持 GET / 与 POST /upload"))
            }
        } catch (t: Throwable) {
            RunLog.w(TAG, "处理请求失败：" + t.javaClass.simpleName + " " + (t.message ?: ""))
        } finally {
            activeRequests.decrementAndGet()
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    // ==================== 上传处理 ====================

    /**
     * 上传入口：同一时刻只允许一个上传。
     *
     * 两个并发上传同名文件会互相覆盖 .part，最终得到一个必然校验失败的包；
     * 直接拒绝第二个比「看起来在传、其实在写坏」诚实得多。
     */
    private suspend fun receiveUpload(
        input: InputStream,
        respond: (ByteArray) -> Unit,
        request: Request,
        clientIp: String,
    ) {
        if (activeUploads.incrementAndGet() > 1) {
            activeUploads.decrementAndGet()
            respond(simpleResponse(409, "已有另一个上传在进行，请等它完成后再试"))
            return
        }
        try {
            receiveUploadInner(input, respond, request, clientIp)
        } finally {
            activeUploads.decrementAndGet()
        }
    }

    private suspend fun receiveUploadInner(
        input: InputStream,
        respond: (ByteArray) -> Unit,
        request: Request,
        clientIp: String,
    ) {
        if (_state.value.state != State.RUNNING) {
            respond(simpleResponse(503, "推送服务已停止"))
            return
        }
        val contentLength = request.contentLength
        if (contentLength <= 0L) {
            // 浏览器用 fetch/XHR 传 File 时一定带 Content-Length；
            // 没有长度就无法校验完整性，宁可拒绝也不写一个不知道对错的文件
            respond(simpleResponse(411, "缺少 Content-Length，无法校验完整性，请用页面里的上传按钮"))
            return
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            respond(simpleResponse(413, "文件太大（上限 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MB）"))
            return
        }

        val fileName = sanitizeApkName(request.query["name"].orEmpty())
        val dir = directory().apply { mkdirs() }
        // 独立文件避免后续同名上传覆盖安装器仍在读取的 APK。
        val target = File.createTempFile("push_", "_" + fileName, dir)
        target.delete()
        val part = File(dir, target.name + ".part")

        val startedAt = System.currentTimeMillis()
        RunLog.step(
            "手机推送开始：" + fileName + "（" + (contentLength / 1024) + " KB）来自 " + clientIp,
        )
        _state.value = _state.value.copy(
            receiving = true,
            receivedBytes = 0L,
            expectedBytes = contentLength,
            lastFileName = fileName,
            lastClientIp = clientIp,
            message = "正在接收：" + fileName,
        )

        var received = 0L
        var lastTick = System.currentTimeMillis()
        try {
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (received < contentLength) {
                    val want = minOf(buffer.size.toLong(), contentLength - received).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    received += read

                    val now = System.currentTimeMillis()
                    if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                        lastTick = now
                        _state.value = _state.value.copy(receivedBytes = received)
                    }
                }
                out.flush()
            }
        } catch (t: Throwable) {
            part.delete()
            _state.value = _state.value.copy(
                receiving = false,
                receivedBytes = 0L,
                message = "上传中断（已收到 " + received + " 字节）：" +
                    t.javaClass.simpleName + " " + (t.message ?: ""),
            )
            RunLog.fail(
                "手机推送中断：" + fileName + " 已收到 " + received + " / " + contentLength +
                    " 字节，来源 " + clientIp + "，未完成分片已删除",
            )
            runCatching { respond(simpleResponse(500, "上传中断，请重新上传")) }
            return
        }

        if (received != contentLength) {
            part.delete()
            _state.value = _state.value.copy(
                receiving = false,
                receivedBytes = 0L,
                message = "上传不完整（" + received + " / " + contentLength + " 字节），已丢弃",
            )
            RunLog.fail(
                "手机推送不完整：" + fileName + " " + received + " / " + contentLength +
                    " 字节，来源 " + clientIp + "，已删除不完整文件",
            )
            respond(simpleResponse(400, "上传不完整，请重新上传"))
            return
        }

        if (!desired || !currentCoroutineContext().isActive) {
            part.delete()
            return
        }
        if (!part.renameTo(target)) {
            part.delete()
            _state.value = _state.value.copy(
                receiving = false,
                message = "落盘失败（存储空间不足或权限异常）",
            )
            RunLog.fail("手机推送落盘失败：" + target.absolutePath)
            respond(simpleResponse(500, "车机落盘失败，请检查存储空间"))
            return
        }

        val costMs = System.currentTimeMillis() - startedAt
        val speedKb = if (costMs > 0) (received / 1024) * 1000 / costMs else 0
        _state.value = _state.value.copy(
            receiving = false,
            receivedBytes = received,
            expectedBytes = contentLength,
            lastFileName = fileName,
            lastSizeBytes = received,
            lastClientIp = clientIp,
            lastCostMs = costMs,
            message = "已收到 " + fileName + "（" + (received / 1024) + " KB，耗时 " +
                (costMs / 1000.0).toInt() + " 秒），车机开始安装",
        )
        RunLog.ok(
            "手机推送完成：" + fileName + "（" + (received / 1024) + " KB）来自 " + clientIp +
                "，耗时 " + costMs + " ms（约 " + speedKb + " KB/s）→ " + target.absolutePath,
        )
        val body = ("{\"ok\":true,\"file\":\"" + jsonEscape(fileName) + "\",\"size\":" + received + "}")
        respond(response(200, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8)))

        // 先回包再进入安装，手机端不用干等安装结果
        if (desired) onUploaded?.invoke(target, received, clientIp, costMs)
    }

    // ==================== HTTP 解析与应答 ====================

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val contentLength: Long,
    )

    /**
     * 读取请求头（到空行为止）。
     *
     * 逐字节读 + 上限保护：HTTP 头必须完整，但不能让一个乱发数据的客户端把内存吃满。
     * 头部按 ISO-8859-1 解码（HTTP 头是字节流），查询参数里的中文文件名
     * 由 URLDecoder 单独按 UTF-8 解。
     */
    private fun readHeaderBlock(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var last1 = -1
        var last2 = -1
        var last3 = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            if (out.size() > MAX_HEADER_BYTES) return null
            if (last1 == 13 && last2 == 10 && last3 == 13 && b == 10) break
            if (last3 == 10 && b == 10) break
            last1 = last2
            last2 = last3
            last3 = b
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun parseRequest(header: String): Request {
        val lines = header.split("\r\n", "\n")
        val firstLine = lines.firstOrNull { it.isNotBlank() }.orEmpty()
        val parts = firstLine.split(" ")
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val target = parts.getOrNull(1).orEmpty()
        val path = target.substringBefore('?').ifBlank { "/" }
        val query = parseQuery(target.substringAfter('?', ""))
        val contentLength = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull()
            ?: -1L
        return Request(method, path, query, contentLength)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            result[decode(key)] = decode(value)
        }
        return result
    }

    private fun decode(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun diagnosticResponse(path: String): ByteArray {
        val report = diagnosticReports.current()
            ?: return simpleResponse(404, "报告未生成、已过期或已清除。请在车机诊断页重新生成报告。")
        return response(200, "text/plain; charset=utf-8", report.toByteArray(Charsets.UTF_8),
            if (path == "/diagnostics.txt") "attachment; filename=car-diagnostics.txt" else null)
    }

    private fun response(
        code: Int,
        contentType: String,
        body: ByteArray,
        disposition: String? = null,
    ): ByteArray {
        val head = ("HTTP/1.1 " + code + " " + statusText(code) + "\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Content-Length: " + body.size + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            (disposition?.let { "Content-Disposition: $it\r\n" } ?: "") +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        return head + body
    }

    private fun simpleResponse(code: Int, text: String): ByteArray =
        response(code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun writeAll(socket: Socket, bytes: ByteArray) {
        runCatching {
            socket.getOutputStream().use { it.write(bytes); it.flush() }
        }
    }

    private fun jsonEscape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 只接受局域网 / 链路本地 / 本机回环来源，防御性校验 */
    private fun isLanClient(address: InetAddress?): Boolean {
        address ?: return false
        return runCatching {
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
        }.getOrDefault(false)
    }

    // ==================== 文件名与网络信息 ====================

    /**
     * 清洗上传文件名。
     *
     * 客户端只可能通过 name 参数提供「文件名」，这里做三件事：
     * 1. 剥掉任何目录部分（/ 与 \），再清掉 .. —— 彻底杜绝路径穿越
     * 2. 只保留中英数字与 . _ -，其它字符统一换成 _（避免 ROM 文件系统报错）
     * 3. 强制 .apk 后缀并限制长度
     */
    private fun sanitizeApkName(raw: String): String {
        val name = decode(raw)
        val noDir = name.substringAfterLast('/').substringAfterLast('\\')
        val noTraverse = noDir.replace("..", "_")
        val cleaned = noTraverse.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            .trim('_', '.', ' ')
        val base = if (cleaned.isBlank()) {
            "push_" + System.currentTimeMillis() + ".apk"
        } else if (cleaned.endsWith(".apk", ignoreCase = true)) {
            cleaned
        } else {
            cleaned + ".apk"
        }
        return if (base.length > 64) base.takeLast(64) else base
    }

    /**
     * 读取当前网络状态。
     *
     * - IPv4 / 网关：从当前默认网络的 LinkProperties 里取（每次实时读）
     * - SSID：优先 WifiManager.connectionInfo；Android 8.1+ 没有定位权限时
     *   系统会返回 <unknown ssid>，这里如实置空，UI 显示「SSID 不可见」
     *   （不为了一个名字去申请定位权限 —— 车机场景不值得）
     */
    private fun readLanStatus(): LanStatus {
        val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

        var connectedViaWifi = false
        var ipAddress: String? = null
        var gateway: String? = null

        runCatching {
            val cm = connectivity ?: return@runCatching
            // 无互联网的手机热点可能不是默认网络，优先读取已连 WiFi。
            val network = cm.allNetworks.firstOrNull { candidate ->
                cm.getNetworkCapabilities(candidate)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    cm.getLinkProperties(candidate)?.linkAddresses?.any {
                        it.address is Inet4Address && it.address.isSiteLocalAddress
                    } == true
            } ?: cm.activeNetwork ?: return@runCatching
            val caps = cm.getNetworkCapabilities(network)
            val link = cm.getLinkProperties(network)
            connectedViaWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            ipAddress = link?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
                ?.address?.hostAddress

            gateway = link?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway?.hostAddress
        }

        return LanStatus(
            wifiEnabled = wifiEnabled,
            connectedViaWifi = connectedViaWifi,
            ssid = if (connectedViaWifi) readSsid() else "",
            ipAddress = ipAddress,
            gateway = gateway,
        )
    }

    /**
     * SSID 可能被系统隐藏（无定位权限），读不到就返回空串，绝不编造。
     *
     * WifiManager.getConnectionInfo() 自 API 31 起被标记弃用（推荐 transportInfo），
     * 但车机上还有 Android 8/9，且这条老接口在各版本上语义一致，保留它最稳；
     * 新的 transportInfo 同样受定位权限限制，换过去并不会让 SSID 更可见。
     */
    @Suppress("DEPRECATION")
    private fun readSsid(): String {
        val raw = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull().orEmpty()
        val cleaned = raw.trim().removeSurrounding("\"")
        return if (cleaned.isEmpty() ||
            cleaned.equals("<unknown ssid>", ignoreCase = true) ||
            cleaned.startsWith("0x")
        ) {
            ""
        } else {
            cleaned
        }
    }

    // ==================== 手机上看到的页面 ====================

    /**
     * 上传页（内联 HTML/CSS/JS，中文，手机优先）。
     *
     * 刻意做成「一个字符串」而不是 assets 里的文件：页面很小、不需要资源系统，
     * 放在这里能保证它和服务端逻辑永远同步（改了接口不可能忘记改页面）。
     * 手机上不需要装任何 App —— 这是整个方案的核心卖点。
     */
    private fun uploadPage(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>车机助手 · APK 推送</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin: 0; padding: 20px 18px 32px; background: #101418; color: #E4E7EA;
         font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
  h1 { font-size: 21px; margin: 0 0 6px; }
  .sub { color: #B6BEC6; font-size: 14px; line-height: 1.5; }
  .card { background: #171C21; border-radius: 16px; padding: 18px; margin-top: 14px; }
  .warn { background: #2A2413; color: #FFB020; font-size: 13px; line-height: 1.6; }
  .steps { font-size: 13px; color: #B6BEC6; line-height: 1.9; margin: 0; padding-left: 20px; }
  .filebox { display: block; width: 100%; padding: 18px 14px; background: #22282E;
             border: 1px dashed #4A545E; border-radius: 14px; color: #E4E7EA;
             font-size: 16px; text-align: center; }
  input[type=file] { display: none; }
  .name { margin-top: 12px; font-size: 15px; color: #E4E7EA; word-break: break-all; }
  .meta { margin-top: 4px; font-size: 13px; color: #B6BEC6; }
  button { margin-top: 16px; width: 100%; padding: 18px; font-size: 18px; font-weight: 700;
           color: #00182E; background: #4DA3FF; border: 0; border-radius: 14px; }
  button:disabled { background: #3C444C; color: #9AA4AE; }
  .track { height: 12px; background: #22282E; border-radius: 8px; overflow: hidden; margin-top: 18px; }
  .bar { height: 100%; width: 0%; background: #4DA3FF; transition: width .15s linear; }
  .status { margin-top: 12px; font-size: 15px; line-height: 1.5; color: #B6BEC6; min-height: 22px; }
  .ok { color: #4CD964; } .err { color: #FF453A; }
  .footer { margin-top: 18px; font-size: 12px; color: #8E979F; line-height: 1.7; }
</style>
</head>
<body>
  <h1>车机助手 · 手机推送安装</h1>
  <div class="card"><strong>车机诊断报告（只读）</strong><p>先在车机「车机诊断」页点击生成报告；10 分钟内可取，刷新诊断或关闭服务即清除。页面是采集时快照，并非实时状态。</p><a href="/diagnostics">查看报告</a> · <a href="/diagnostics.txt">下载 TXT 报告</a></div>
  <div class="sub">车机连着你的手机热点，所以直接从这里把 APK 推过去就行。</div>

  <div class="card warn">
    本页面只在车机所在的局域网内可见。上传的 APK 会保存到车机的应用专属目录，
    然后由车机助手自动走安装流程 —— 无 root 的车机上还需要你在车机屏幕上点一次「安装」。
  </div>

  <div class="card">
    <label class="filebox" for="file">📦 点这里选择 APK 文件</label>
    <input id="file" type="file" accept=".apk,application/vnd.android.package-archive" onchange="picked()">
    <div class="name" id="name">还没有选择文件</div>
    <div class="meta" id="size"></div>

    <button id="send" onclick="startUpload()">推送到车机</button>

    <div class="track"><div class="bar" id="bar"></div></div>
    <div class="status" id="status">等待选择文件…</div>
  </div>

  <div class="card">
    <div class="sub" style="margin-bottom:8px">手机上只需要三步：</div>
    <ol class="steps">
      <li>选一个 .apk 文件</li>
      <li>点「推送到车机」，等进度条走完</li>
      <li>看车机屏幕：确认安装结果（无 root 时需在车机上点「安装」）</li>
    </ol>
  </div>

  <div class="footer">
    上传过程中请不要锁屏或切走浏览器。网络中断时车机会删除未完成的分片并提示已收到多少字节，
    重新上传同一个文件即可。
  </div>

<script>
var busy = false;
function fmt(n) {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}
function setStatus(text, cls) {
  var el = document.getElementById('status');
  el.textContent = text;
  el.className = 'status' + (cls ? ' ' + cls : '');
}
function picked() {
  var f = document.getElementById('file').files[0];
  var nameEl = document.getElementById('name');
  var sizeEl = document.getElementById('size');
  if (!f) {
    nameEl.textContent = '还没有选择文件';
    sizeEl.textContent = '';
    setStatus('等待选择文件…');
    return;
  }
  nameEl.textContent = f.name;
  sizeEl.textContent = '大小：' + fmt(f.size);
  setStatus('已选择，点下面的按钮开始推送');
}
function startUpload() {
  if (busy) return;
  var file = document.getElementById('file').files[0];
  if (!file) { setStatus('请先选择 APK 文件', 'err'); return; }
  if (!/.apk$/i.test(file.name)) { setStatus('请选择 .apk 文件', 'err'); return; }
  if (file.size <= 0) { setStatus('文件为空', 'err'); return; }

  busy = true;
  var button = document.getElementById('send');
  var bar = document.getElementById('bar');
  button.disabled = true;
  button.textContent = '正在推送…';
  bar.style.width = '0%';

  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name));
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');

  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) {
      var p = Math.round(e.loaded * 100 / e.total);
      bar.style.width = p + '%';
      setStatus('已发送 ' + fmt(e.loaded) + ' / ' + fmt(e.total) + '（' + p + '%）');
    } else {
      setStatus('已发送 ' + fmt(e.loaded));
    }
  };
  xhr.onload = function () {
    busy = false;
    button.disabled = false;
    button.textContent = '推送到车机';
    if (xhr.status === 200) {
      bar.style.width = '100%';
      setStatus('✅ 车机已收到，正在安装 —— 请看车机屏幕确认结果', 'ok');
    } else {
      setStatus('❌ 车机拒绝了这次上传（HTTP ' + xhr.status + '）：' + xhr.responseText, 'err');
    }
  };
  xhr.onerror = function () {
    busy = false;
    button.disabled = false;
    button.textContent = '推送到车机';
    setStatus('❌ 上传失败：连接中断。请确认手机与车机在同一热点，然后重试', 'err');
  };
  setStatus('正在推送 ' + file.name + '（' + fmt(file.size) + '）…');
  xhr.send(file);
}
</script>
</body>
</html>
"""
}
