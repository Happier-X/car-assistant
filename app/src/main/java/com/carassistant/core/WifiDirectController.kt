package com.carassistant.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Public APIs only. Never globally cancel discovery/negotiation or remove a shared P2P group. */
@SuppressLint("MissingPermission") // target28 uses FINE_LOCATION; checked before protected calls, exceptions retained.
class WifiDirectController(private val context: Context) {
    companion object {
        const val LIMITS = "Wi-Fi Direct 没有供普通应用强制开启的公开总开关。请先在系统 WiFi 设置开启 WiFi，再手动发现、选择连接或创建直连组。P2P 与热点/WiFi/CarLink 共享无线资源，用户操作可能影响现有连接；本应用不主动切断热点，也不自动建组。系统请求受理不等于已连接。公开 API 无法证明直连组属于哪个 App，因此不提供全局停止发现、取消协商或移除组。离页只结束本页观察并释放自身通道，系统操作可能继续；系统在最后一个客户端退出时也可能清理 P2P。P2P 不等于手机热点，不保证现有 HTTP 服务能取得或使用其地址。报告不含设备 MAC、设备名称、组名、密码或连接凭据。"
        fun hasLocation(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun locationEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        fun capabilityFields(context: Context): List<CarDiagnostics.Field> {
            val fields = mutableListOf<CarDiagnostics.Field>()
            fun read(label: String, source: String, action: () -> String) {
                val value = try { action() } catch (e: Exception) { "不可读取（${e.javaClass.simpleName}）" }
                fields += CarDiagnostics.Field(label, value, source)
            }
            read("Wi-Fi Direct 系统特性", "PackageManager.FEATURE_WIFI_DIRECT") { if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) "支持" else "系统未声明支持" }
            read("Wi-Fi Direct 定位权限", "target28：ACCESS_FINE_LOCATION；仅前台 P2P 所需，不读取坐标") { if (hasLocation(context)) "精确位置权限已授予" else "未授予精确位置权限，发现/连接/组状态读取可能被拒绝" }
            read("系统定位开关", "LocationManager.isLocationEnabled / provider 状态；不请求坐标") { if (locationEnabled(context)) "已开启" else "已关闭，设备发现可能不可用" }
            read("WiFi 开关（不是 P2P 总开关）", "WifiManager.isWifiEnabled") {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifi?.let { if (it.isWifiEnabled) "已开启" else "已关闭，请到系统设置手动开启" } ?: "未知 / 无 WiFi 服务"
            }
            return fields
        }
        fun errorReason(code: Int) = when (code) {
            WifiP2pManager.ERROR -> "ERROR：系统内部错误"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED：系统报告不支持"
            WifiP2pManager.BUSY -> "BUSY：系统 P2P 忙碌"
            else -> "未知错误"
        }
    }

    data class Peer(val id: Long, val label: String, val status: String)
    data class Snapshot(
        val observing: Boolean = false,
        val busy: Boolean = false,
        val message: String = "尚未打开 Wi-Fi Direct 观察通道",
        val enabled: String = "未知",
        val discovery: String = "未知",
        val group: String = "未知",
        val role: String = "未知",
        val peers: List<Peer> = emptyList(),
        val time: String = "未操作",
        val result: String = "未操作",
        val error: String = "无",
    ) {
        fun reportFields() = listOf(
            CarDiagnostics.Field("P2P 观察通道", if (observing) "本应用通道与广播监听已打开" else "未观察 / 通道已关闭，下面为最近状态", "本应用 channel/receiver 生命周期"),
            CarDiagnostics.Field("P2P 启用状态", enabled, "requestP2pState / WIFI_P2P_STATE_CHANGED_ACTION"),
            CarDiagnostics.Field("P2P 系统发现状态", discovery, "requestDiscoveryState / DISCOVERY_CHANGED；不代表本 App 独占"),
            CarDiagnostics.Field("P2P 系统组状态", group, "requestConnectionInfo.groupFormed + requestGroupInfo；不推断创建 App"),
            CarDiagnostics.Field("P2P 本机角色", role, "公开连接/组状态，不导出 IP / 凭据"),
            CarDiagnostics.Field("P2P 已读取设备数", peers.size.toString(), "仅导出数量，不导出设备名或地址"),
            CarDiagnostics.Field("P2P 最近操作时间", time, "用户操作发起时间"),
            CarDiagnostics.Field("P2P 最近操作结果", result, "真实 ActionListener / 状态读取；请求受理不等于连接"),
            CarDiagnostics.Field("P2P 最近错误", error, "失败数字码及含义 / 异常类型，不导出异常原文"),
            CarDiagnostics.Field("P2P 限制", LIMITS, "本应用安全边界"),
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val gate = P2pOperationGate()
    private val _state = MutableStateFlow(Snapshot())
    val state = _state.asStateFlow()
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var released = false
    private var epoch = 0L
    private var readVersion = 0L
    private var topologyVersion = 0L
    private var peerSerial = 0L
    private val peerAddresses = mutableMapOf<Long, String>() // Needed only in memory for WifiP2pConfig.
    private var deadline: Runnable? = null
    private var poll: Runnable? = null
    private var awaitingGroup: Long? = null

    private fun valid(e: Long, c: WifiP2pManager.Channel) = !released && epoch == e && channel === c
    private fun post(e: Long, c: WifiP2pManager.Channel, work: () -> Unit) {
        handler.post { if (valid(e, c)) work() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (channel == null) return
            ++topologyVersion
            refresh()
        }
    }

    fun enter() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { enter() }; return }
        if (released || channel != null) return
        if (Build.VERSION.SDK_INT < 29) {
            _state.value = _state.value.copy(message = "Android 10 以下本页仅提供能力检测和系统设置入口，不初始化直连操作通道")
            return
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            _state.value = _state.value.copy(message = "系统未声明 Wi-Fi Direct 支持，仍可尝试系统 WiFi 设置")
            return
        }
        try {
            val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: run { _state.value = _state.value.copy(message = "系统未提供 WifiP2pManager"); return }
            val e = ++epoch
            val c = m.initialize(context, Looper.getMainLooper()) {
                if (epoch == e && !released) {
                    leave()
                    _state.value = _state.value.copy(message = "P2P 通道已断开；请手动刷新重建，未自动重试操作", error = "ChannelListener.onChannelDisconnected")
                }
            } ?: run { _state.value = _state.value.copy(message = "P2P 通道初始化返回空"); return }
            manager = m
            channel = c
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            _state.value = _state.value.copy(observing = true, message = "P2P 通道已建立，仅查询状态，未开始发现或连接", enabled = "未知", discovery = "未知", group = "未知", role = "未知", peers = emptyList())
            refresh()
        } catch (e: Exception) {
            leave()
            _state.value = _state.value.copy(message = "P2P 初始化不可用（${e.javaClass.simpleName}），可尝试系统设置", error = e.javaClass.simpleName)
        }
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { refresh() }; return }
        val m = manager ?: return
        val c = channel ?: return
        val e = epoch
        val version = ++readVersion
        _state.value = _state.value.copy(group = "正在查询 / 尚未确认", role = "未知")
        fun update(work: () -> Unit) = post(e, c) { if (readVersion == version) work() }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                m.requestP2pState(c) { value -> update { _state.value = _state.value.copy(enabled = when (value) { WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "已启用（系统状态）"; WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "未启用（系统状态）"; else -> "未知系统状态值：$value" }) } }
                m.requestDiscoveryState(c) { value -> update { _state.value = _state.value.copy(discovery = when (value) { WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> "系统正在发现（不保证由本应用发起）"; WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> "系统未在发现"; else -> "未知系统状态值：$value" }) } }
            }
            if (!hasLocation(context)) {
                _state.value = _state.value.copy(group = "未知：缺少精确位置权限", role = "未知", peers = emptyList(), message = "请主动授权精确位置并开启系统定位后刷新；未读取坐标")
                peerAddresses.clear()
                return
            }
            var formed: Boolean? = null
            var groupExists: Boolean? = null
            var owner: Boolean? = null
            fun merge() {
                if (formed == null || groupExists == null) return
                val exists = formed == true || groupExists == true
                _state.value = _state.value.copy(group = if (exists) "已观察到系统直连组（创建 App 无法确认）" else "未观察到已形成的直连组", role = if (!exists) "无已形成的组" else if (owner == true) "本机为组主（GO），不代表本应用拥有组" else "本机为客户端 / 角色未知")
                val token = awaitingGroup
                if (exists && token != null && gate.matches(token)) {
                    finish(token, "已观察到系统直连组形成；无法仅凭公开 API 确认由本应用造成或目标设备已完成加入")
                }
            }
            m.requestConnectionInfo(c) { info -> update { formed = info?.groupFormed; owner = info?.isGroupOwner; merge() } }
            m.requestGroupInfo(c) { group -> update { groupExists = group != null; if (group != null) owner = group.isGroupOwner; merge() } }
            if (locationEnabled(context)) {
                m.requestPeers(c) { list -> update {
                    peerAddresses.clear()
                    val peers = list?.deviceList.orEmpty().filterNotNull().take(30).mapIndexed { index, device ->
                        val id = ++peerSerial
                        if (device.status == WifiP2pDevice.AVAILABLE && !device.deviceAddress.isNullOrBlank()) peerAddresses[id] = device.deviceAddress
                        val label = device.deviceName?.takeIf { it.isNotBlank() }?.take(50) ?: "未命名设备"
                        Peer(id, "设备 ${index + 1} · $label", when (device.status) {
                            WifiP2pDevice.AVAILABLE -> "可邀请"
                            WifiP2pDevice.INVITED -> "系统已邀请 / 协商中"
                            WifiP2pDevice.CONNECTED -> "系统已连接"
                            WifiP2pDevice.FAILED -> "系统记录失败"
                            else -> "不可用 / 未知"
                        })
                    }
                    _state.value = _state.value.copy(peers = peers)
                } }
            } else {
                peerAddresses.clear()
                _state.value = _state.value.copy(peers = emptyList(), message = "系统定位关闭，无法可靠发现设备；请先在定位设置开启")
            }
        } catch (ex: Exception) {
            peerAddresses.clear()
            _state.value = _state.value.copy(group = "未知：读取受限", role = "未知", peers = emptyList(), message = "P2P 状态读取异常（${ex.javaClass.simpleName}）", error = ex.javaClass.simpleName)
        }
    }

    /** Only explicit UI actions enter here; every operation checks fresh shared system state. */
    fun act(kind: String, peerId: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { act(kind, peerId) }; return }
        if (released || gate.busy) return
        val m = manager
        val c = channel
        val label = when (kind) { "discover" -> "发现设备"; "connect" -> "连接设备"; "create" -> "创建直连组"; else -> return }
        _state.value = _state.value.copy(time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()), error = "无")
        try {
            if (m == null || c == null) { _state.value = _state.value.copy(message = "观察通道未就绪，请先刷新", result = "$label 未发起"); return }
            if (!hasLocation(context) || !locationEnabled(context)) { _state.value = _state.value.copy(message = "需要精确位置权限及系统定位开启；未发起操作", result = "$label 未发起：权限/定位未就绪"); return }
            if (Build.VERSION.SDK_INT < 29) { _state.value = _state.value.copy(message = "该系统无法通过本页所需公开 API 完整确认共享 P2P 占用状态，请使用系统 WiFi 设置", result = "$label 未发起：状态不可安全确认"); return }
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi?.isWifiEnabled != true) { _state.value = _state.value.copy(message = "WiFi 未开启或不可读取，请到系统设置手动开启", result = "$label 未发起"); return }
            val address = if (kind == "connect") peerAddresses[peerId] else null
            if (kind == "connect" && address == null) { _state.value = _state.value.copy(message = "设备列表已变化或该设备不可邀请，请刷新后重新选择", result = "连接未发起"); return }
            val token = gate.begin() ?: return
            val e = epoch
            val topology = topologyVersion
            _state.value = _state.value.copy(busy = true, message = "正在只读检查现有组与协商状态；尚未发出$label 请求", result = "$label：检查共享状态")
            armTimeout(token, 5_000, "$label 前状态查询超时，未发起操作")
            var connection: Boolean? = null
            var group: Boolean? = null
            var networking: Boolean? = null
            var enabled: Boolean? = null
            var discovery: Boolean? = null
            var submitted = false
            fun attempt() {
                if (submitted || !gate.matches(token) || connection == null || group == null || networking == null || enabled == null || discovery == null) return
                submitted = true
                if (topologyVersion != topology) { finish(token, "检查期间系统状态发生变化，未发起$label；请刷新后重试"); return }
                val refusal = P2pOperationGate.refusal(group == true || connection == true, networking, enabled)
                if (refusal != null) { finish(token, refusal); return }
                if (kind == "discover" && discovery == true) { finish(token, "系统已有发现活动，仅刷新已有设备，不重新发起或停止共享发现"); refresh(); return }
                armTimeout(token, 8_000, "$label 启动回调超时，系统结果未知；本应用未全局取消，请到系统设置查看")
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = post(e, c) {
                        if (!gate.matches(token)) return@post
                        if (kind == "discover") {
                            finish(token, "发现请求已受理；是否正在发现及设备列表以系统状态为准")
                            refresh()
                        } else {
                            awaitingGroup = token
                            _state.value = _state.value.copy(message = "$label 请求已受理，正在等待系统成组；尚不能称为已连接", result = "$label：ActionListener.onSuccess，仅请求受理")
                            armTimeout(token, 20_000, "$label 请求受理后 20 秒未确认成组；结果未知，未全局取消协商")
                            schedulePoll(token, e, c)
                        }
                    }
                    override fun onFailure(reason: Int) = post(e, c) {
                        if (gate.matches(token)) {
                            val error = "$reason / ${errorReason(reason)}"
                            finish(token, "$label 请求失败：$error", error)
                            refresh()
                        }
                    }
                }
                try {
                    when (kind) {
                        "discover" -> m.discoverPeers(c, listener)
                        "create" -> m.createGroup(c, listener)
                        "connect" -> m.connect(c, WifiP2pConfig().apply { deviceAddress = address }, listener)
                    }
                } catch (ex: Exception) { finish(token, "$label 调用异常（${ex.javaClass.simpleName}），不推断硬件不支持", ex.javaClass.simpleName) }
            }
            fun receive(work: () -> Unit) = post(e, c) { if (gate.matches(token)) { work(); attempt() } }
            try {
                m.requestConnectionInfo(c) { info -> receive { connection = info?.groupFormed } }
                m.requestGroupInfo(c) { info -> receive { group = info != null } }
                m.requestNetworkInfo(c) { info -> receive {
                    networking = when (info?.state) {
                        android.net.NetworkInfo.State.DISCONNECTED -> false
                        null, android.net.NetworkInfo.State.UNKNOWN -> null
                        else -> true // Connecting, connected, disconnecting or suspended are not safe idle states.
                    }
                } }
                m.requestP2pState(c) { value -> receive { enabled = when (value) { WifiP2pManager.WIFI_P2P_STATE_ENABLED -> true; WifiP2pManager.WIFI_P2P_STATE_DISABLED -> false; else -> null } } }
                m.requestDiscoveryState(c) { value -> receive { discovery = when (value) { WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> true; WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> false; else -> null } } }
            } catch (ex: Exception) { finish(token, "$label 前状态读取异常（${ex.javaClass.simpleName}），未发起操作", ex.javaClass.simpleName) }
        } catch (ex: Exception) {
            _state.value = _state.value.copy(message = "$label 不可用（${ex.javaClass.simpleName}）", error = ex.javaClass.simpleName, result = "$label 未发起")
        }
    }

    private fun armTimeout(token: Long, ms: Long, message: String) {
        deadline?.let(handler::removeCallbacks)
        deadline = Runnable { if (gate.matches(token)) { finish(token, message, "等待超时 / 未确认，不是系统失败码"); refresh() } }.also { handler.postDelayed(it, ms) }
    }
    private fun schedulePoll(token: Long, e: Long, c: WifiP2pManager.Channel) {
        if (!gate.matches(token) || !valid(e, c)) return
        refresh()
        poll?.let(handler::removeCallbacks)
        poll = Runnable { schedulePoll(token, e, c) }.also { handler.postDelayed(it, 2_000) }
    }
    private fun finish(token: Long, result: String, error: String = "无") {
        if (!gate.finish(token)) return
        deadline?.let(handler::removeCallbacks)
        poll?.let(handler::removeCallbacks)
        deadline = null; poll = null; awaitingGroup = null
        _state.value = _state.value.copy(busy = false, message = result, result = result, error = error)
    }

    fun leave() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { leave() }; return }
        ++epoch; ++readVersion
        val pending = gate.busy
        gate.invalidate()
        deadline?.let(handler::removeCallbacks); poll?.let(handler::removeCallbacks)
        deadline = null; poll = null; awaitingGroup = null
        peerAddresses.clear()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        val own = channel
        channel = null; manager = null
        val failure = runCatching { if (Build.VERSION.SDK_INT >= 27) own?.close() }.exceptionOrNull()
        _state.value = _state.value.copy(observing = false, busy = false, peers = emptyList(), message = "本页观察已结束，只释放自身通道；未调用全局取消或拆组。系统操作仍可能继续", result = if (pending) "离页/后台结束本页等待，系统请求最终结果未确认" else _state.value.result, error = failure?.javaClass?.simpleName ?: _state.value.error)
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { release() }; return }
        released = true
        leave()
    }
}
