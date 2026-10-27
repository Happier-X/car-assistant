package com.carassistant.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Foreground only, no MAC/name collection, no connections, no system-wide stop operations. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    companion object {
        const val LIMITS = "本应用运行在车机上：本扫描只覆盖接收方向（设备→车机），不能证明本机广播被手机扫到；车机自扫偶发捕获自家广播不可靠，不作为证据；同时广播+扫描可能降低广播成功率，建议两件事分开做。仅手动前台扫描 15 秒，最多 100 条结果；达到上限即停止。每条平台原始记录截取前 512 字节，厂商/Service Data 各最多 16 项、4096 字符，UUID 最多 32 项，超出部分省略，显示长度为平台原长度。RSSI 不代表距离或兼容性。ScanResult.scanRecord 是平台提供的广播与扫描响应组合，bytes 是此记录的原始字节，无法独立区分 scan response。无结果可能是权限/定位/系统节流/无线环境问题；密集回调超过处理队列上限时丢弃部分结果并在下方计数，不代表未广播。CarLink 私有内部广告内容未知，不能把附近结果自动归属于 CarLink；比较本机广播须用另一设备观察。UUID、厂商数据和原始字节可能包含身份标识，仅在本地显示；默认导出仅数量/长度，不含载荷。"
        fun failure(code: Int) = when (code) {
            1 -> "SCAN_FAILED_ALREADY_STARTED"
            2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
            3 -> "SCAN_FAILED_INTERNAL_ERROR"
            4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
            5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
            6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
            else -> "未知扫描失败"
        }
        private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
    }
    data class Row(val time: String, val observedNanos: Long, val rssi: Int, val connectable: String,
        val uuids: String, val manufacturer: String, val service: String, val raw: String, val byteCount: Int) {
        fun summary() = "$time · bootNs=$observedNanos · RSSI=$rssi dBm · 可连接=$connectable · 平台记录=$byteCount 字节"
        fun detail() = "${summary()}\nUUID=$uuids\n厂商数据=$manufacturer\nService Data=$service\n平台原始字节=$raw"
    }
    data class Snapshot(val message: String = "尚未开始扫描", val busy: Boolean = false, val rows: List<Row> = emptyList(), val error: String = "无", val dropped: Int = 0, val attempted: Boolean = false) {
        fun report(raw: Boolean = false) = buildString {
            appendLine("BLE 限时扫描（接收方向：设备→车机）：$message；本次已发起扫描=${if (attempted) "是" else "否"}；${rows.size} 条；丢弃密集回调 $dropped 条；未读取设备 MAC/名称")
            appendLine("最近扫描错误：$error")
            rows.forEach { appendLine(if (raw) it.detail() else it.summary()) }
            append(LIMITS)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val gate = BleScanSession()
    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var released = false
    private var foreground = false
    private var registered = false
    private var deadline = 0L
    private class Unavailable(message: String) : Exception(message)
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)
    private val dropped = java.util.concurrent.atomic.AtomicInteger(0)
    private val timeout = Runnable { stop("15 秒时限结束") }
    private var cleanupRetries = 0
    private val cleanupRetry = Runnable { stop("自动重试自身扫描清理") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) stop("蓝牙关闭/切换")
            if (intent?.action == LocationManager.MODE_CHANGED_ACTION && !locationEnabled()) stop("定位关闭或状态不可读")
        }
    }
    init {
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(LocationManager.MODE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } catch (e: Exception) { mutable.value = Snapshot("开关监听不可用：${e.javaClass.simpleName}；为保证清理，禁止扫描") }
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) stop("离页/后台，停止自身扫描")
    }
    private fun locationEnabled(): Boolean = try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (android.os.Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (released || !foreground || !registered || gate.token != null) return
        mutable.value = Snapshot("正在检查扫描条件", attempted = true)
        dropped.set(0)
        try {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) throw Unavailable("系统未声明 BLE 支持")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw Unavailable("精确位置权限未授予，请手动授权后重试")
            if (!locationEnabled()) throw Unavailable("定位关闭或不可读取，请在系统设置检查")
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null) throw Unavailable("无蓝牙适配器")
            if (!adapter.isEnabled) throw Unavailable("蓝牙已关闭，请在系统设置开启")
            val source = adapter.bluetoothLeScanner ?: throw Unavailable("系统未提供 BLE 扫描接口")
            val token = gate.begin() ?: return
            cleanupRetries = 0
            scanner = source
            deadline = SystemClock.elapsedRealtime() + 15000
            val own = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    // Drop before posting to avoid an unbounded queue during dense radio traffic.
                    if (queued.incrementAndGet() > 16) { queued.decrementAndGet(); dropped.incrementAndGet(); return }
                    handler.post { try { accept(token, result) } finally { queued.decrementAndGet() } }
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.take(100).forEach { onScanResult(0, it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post { if (gate.accepts(token)) {
                        val error = "onScanFailed=$errorCode / ${failure(errorCode)} @ ${now()}"
                        mutable.value = mutable.value.copy(error = error)
                        stop(error)
                    } }
                }
            }
            callback = own
            mutable.value = Snapshot("${now()} 已提交扫描请求；无启动成功回调，等待观察结果", true)
            handler.postDelayed(timeout, 15000)
            source.startScan(emptyList(), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setReportDelay(0).build(), own)
        } catch (e: Exception) {
            val message = "${now()} 扫描未能开始：${e.javaClass.simpleName}" + if (e is Unavailable) " / ${e.message}" else "（权限或系统限制，请检查设置）"
            mutable.value = mutable.value.copy(error = message)
            if (gate.token != null) stop(message) else mutable.value = Snapshot(message, error = message, attempted = true)
        }
    }
    private fun accept(token: Long, result: ScanResult) {
        if (!gate.accepts(token) || !foreground || released) return
        if (SystemClock.elapsedRealtime() >= deadline) { stop("15 秒时限结束"); return }
        if (mutable.value.rows.size >= 100) { stop("达到 100 条上限"); return }
        try {
            val record = result.scanRecord
            val bytes = record?.bytes
            fun hex(value: ByteArray?) = value?.take(512)?.joinToString(" ") { "%02X".format(it.toInt() and 255) } ?: "无"
            val vendor = record?.manufacturerSpecificData
            val row = Row(now(), result.timestampNanos, result.rssi,
                result.isConnectable.toString(),
                record?.serviceUuids?.take(32)?.joinToString() ?: "无",
                if (vendor == null) "无" else (0 until minOf(vendor.size(), 16)).joinToString { "${vendor.keyAt(it)}:${hex(vendor.valueAt(it))}" }.take(4096),
                record?.serviceData?.entries?.take(16)?.joinToString { "${it.key}:${hex(it.value)}" }?.take(4096) ?: "无",
                hex(bytes), bytes?.size ?: 0)
            mutable.value = mutable.value.copy(rows = mutable.value.rows + row, dropped = dropped.get())
            if (mutable.value.rows.size >= 100) stop("达到 100 条上限")
        } catch (e: Exception) {
            val error = "解析扫描结果异常：${e.javaClass.simpleName} @ ${now()}"
            mutable.value = mutable.value.copy(error = error)
            stop(error)
        }
    }
    fun stop(reason: String = "用户停止") {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (gate.token == null) return
        gate.cancel()
        handler.removeCallbacks(timeout)
        try {
            callback?.let { scanner?.stopScan(it) }
            gate.stopped(true)
            handler.removeCallbacks(cleanupRetry)
            callback = null; scanner = null
            mutable.value = mutable.value.copy(message = "$reason；${now()} 已提交自身停止请求（无停止回调）", busy = false, dropped = dropped.get())
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(message = "$reason；停止异常 ${e.javaClass.simpleName}，实际停止未知；禁止新扫描，请重试停止或在系统关闭蓝牙", busy = true, dropped = dropped.get())
            if (cleanupRetries++ < 3) handler.postDelayed(cleanupRetry, 1000)
        }
    }
    fun clear() { if (gate.token == null) mutable.value = Snapshot() }
    fun release() {
        released = true; foreground = false; stop("ViewModel 释放")
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
