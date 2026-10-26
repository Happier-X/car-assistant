package com.carassistant.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Manual, bounded advertising only. Never scans, connects, enables Bluetooth or reads its address/name. */
@SuppressLint("MissingPermission") // target28 legacy checks + actual SecurityException handling below.
class BleAdvertiserTest(private val context: Context) {
    companion object {
        const val SERVICE_UUID = "9cb329d0-536c-4f32-8ea4-5b9021f67a10"
        const val TIMEOUT_MS = 30_000L
        const val LIMITS = "独立 BLE 测试，不模拟 CarLink 或任何厂商身份。成功回调仅表示系统接受启动，不证明手机收到或 CarLink 兼容。此前 CarLink 日志缺少异常头，不能据此断定硬件不支持。请用另一设备的 BLE 扫描工具实扫：Service Data UUID $SERVICE_UUID，固定载荷 CA1（43 41 31）。广播不含设备名、MAC 或应用生成的唯一标识；蓝牙协议自身仍有无线地址，由系统管理。本应用不采集/导出该地址。"

        fun errorReason(code: Int): String = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE：数据超过广播容量"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS：可用广播实例不足"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED：该回调对应广播已启动"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR：系统蓝牙内部错误"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED：系统报告不支持此广播功能"
            else -> "未知广播错误"
        }

        /** Each source is isolated: an inaccessible field must never become 'unsupported'. */
        fun capabilityFields(context: Context): List<CarDiagnostics.Field> {
            val result = mutableListOf<CarDiagnostics.Field>()
            fun read(label: String, source: String, block: () -> String) {
                val value = try { block() }
                catch (e: SecurityException) { "无权限 / 系统拒绝（${e.javaClass.simpleName}）" }
                catch (e: Exception) { "不可读取（${e.javaClass.simpleName}）" }
                result += CarDiagnostics.Field(label, value, source)
            }
            fun adapter(): BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            read("BLE 系统特性", "PackageManager.FEATURE_BLUETOOTH_LE") {
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "支持" else "系统未声明支持"
            }
            read("蓝牙权限", "targetSdk28：BLUETOOTH / BLUETOOTH_ADMIN；Android12+ 按旧目标兼容规则") {
                val bluetooth = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
                val admin = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
                "BLUETOOTH=${if (bluetooth) "已授予" else "未授予"}；BLUETOOTH_ADMIN=${if (admin) "已授予" else "未授予"}；具体调用仍可能被系统限制"
            }
            read("蓝牙适配器", "BluetoothManager.adapter") { if (adapter() == null) "系统未提供适配器" else "存在" }
            read("蓝牙开关", "BluetoothAdapter.isEnabled") { adapter()?.let { if (it.isEnabled) "已开启" else "已关闭" } ?: "未知：无适配器" }
            read("多实例广播能力", "BluetoothAdapter.isMultipleAdvertisementSupported") {
                adapter()?.let { if (!it.isEnabled) "蓝牙关闭，暂不判断" else if (it.isMultipleAdvertisementSupported) "支持（系统能力报告）" else "不支持（系统能力报告）" } ?: "未知：无适配器"
            }
            read("BLE 广播接口", "BluetoothAdapter.bluetoothLeAdvertiser") {
                adapter()?.let {
                    if (!it.isEnabled) "不可用：蓝牙已关闭"
                    else if (it.bluetoothLeAdvertiser == null) "不可用：系统未提供广播接口（不支持或厂商限制）"
                    else "可取得接口；能否启动以测试回调为准"
                } ?: "不可用：无适配器"
            }
            return result
        }
    }

    data class Snapshot(
        val phase: BleTestSession.Phase = BleTestSession.Phase.IDLE,
        val message: String = "尚未进行 BLE 广播测试",
        val time: String = "未测试",
        val result: String = "未测试",
        val error: String = "无",
        val cleanup: String = "无",
        val receiver: String = "已注册蓝牙开关监听",
        val lateCleanup: String = "无",
        val cleanupBlocked: Boolean = false,
    ) {
        val busy get() = cleanupBlocked || phase == BleTestSession.Phase.STARTING || phase == BleTestSession.Phase.ADVERTISING || phase == BleTestSession.Phase.STOP_FAILED
        fun reportFields() = listOf(
            CarDiagnostics.Field("BLE 最近测试时间", time, "本应用用户手动测试记录，非手机接收记录"),
            CarDiagnostics.Field("BLE 最近测试结果", result, "BluetoothLeAdvertiser 回调 / 本应用状态；不代表当前仍在广播"),
            CarDiagnostics.Field("BLE 最近测试错误", error, "原始回调错误码及含义 / 异常类型，不导出异常原文"),
            CarDiagnostics.Field("BLE 停止记录", cleanup, "仅停止本应用保存的同一 callback；系统无停止成功回调"),
            CarDiagnostics.Field("BLE 开关监听", receiver, "ContextCompat.registerReceiver / ACTION_STATE_CHANGED"),
            CarDiagnostics.Field("BLE 迟到回调清理", lateCleanup, "旧会话仅用自己的 callback 重试停止；异常会阻止新测试"),
            CarDiagnostics.Field("BLE 测试限制", LIMITS, "本应用测试范围"),
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val gate = BleTestSession()
    private val _state = MutableStateFlow(Snapshot())
    val state = _state.asStateFlow()
    private var released = false
    private var registered = false
    private var session: Session? = null
    private val retiredStops = mutableMapOf<Session, String>()

    private inner class Session(val token: Long, val advertiser: BluetoothLeAdvertiser) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        val timeout = Runnable { if (session === this) stop("30 秒测试时限已到") }
        val lateRetry = Runnable { if (retiredStops.containsKey(this)) stop("旧会话时限已到，重试清理") }
        val startupTimeout = Runnable {
            if (session === this && _state.value.phase == BleTestSession.Phase.STARTING) {
                _state.value = _state.value.copy(result = "启动回调等待超时，结果未知", error = "8 秒内未收到启动回调")
                stop("启动回调超时")
            }
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                handler.post {
                    if (!released && session === this@Session && gate.success(token)) {
                        handler.removeCallbacks(startupTimeout)
                        _state.value = _state.value.copy(phase = gate.phase, message = "系统已回调启动成功；最长测试 30 秒，仍需另一设备实扫", result = "onStartSuccess：系统接受广播启动", error = "无")
                    } else if (session === this@Session && gate.phase == BleTestSession.Phase.ADVERTISING && !released) {
                        // Duplicate success for the current active session is harmless.
                    } else {
                        // A cancelled request can complete late. Stop only that old callback.
                        val failure = stopOwn(this@Session)
                        if (session === this@Session) {
                            removeTimers(this@Session)
                            finishStop(this@Session, failure, "迟到回调清理")
                        } else {
                            if (failure != null) {
                                retiredStops[this@Session] = failure
                                val remaining = deadline - SystemClock.elapsedRealtime()
                                if (!released && remaining > 0) {
                                    handler.removeCallbacks(lateRetry)
                                    handler.postDelayed(lateRetry, remaining)
                                }
                            } else {
                                retiredStops.remove(this@Session)
                                handler.removeCallbacks(lateRetry)
                            }
                            updateLateCleanup()
                        }
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                handler.post {
                    if (!released && session === this@Session && gate.failure(token)) {
                        removeTimers(this@Session)
                        session = null
                        val error = "$errorCode / ${errorReason(errorCode)}"
                        _state.value = _state.value.copy(phase = gate.phase, message = "系统回调广播启动失败：$error", result = "onStartFailure", error = error)
                    }
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                stop("蓝牙关闭或正在切换，停止本次测试")
            }
        }
    }

    init {
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        } catch (e: Exception) {
            _state.value = _state.value.copy(receiver = "开关监听不可用（${e.javaClass.simpleName}）；仍有超时与页面离开清理")
        }
    }

    /** All controller operations and callbacks are serialized on the main looper. */
    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { start() }; return }
        if (released || session != null || retiredStops.isNotEmpty()) return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        _state.value = _state.value.copy(time = time, result = "准备测试", error = "无", cleanup = "无")
        try {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                unavailable("系统未声明 FEATURE_BLUETOOTH_LE，未启动测试"); return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                unavailable("无蓝牙读取/管理权限，请检查应用权限或系统限制"); return
            }
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null) { unavailable("系统未提供蓝牙适配器"); return }
            if (!adapter.isEnabled) { unavailable("蓝牙已关闭，请先请求系统开启或在蓝牙设置中开启"); return }
            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) { unavailable("系统未提供 BLE 广播接口，不支持或存在厂商限制"); return }
            val token = gate.begin() ?: return
            val own = Session(token, advertiser)
            session = own
            val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).setConnectable(false).setTimeout(TIMEOUT_MS.toInt()).build()
            // 128-bit Service Data appears once: 2-byte AD header + 16-byte UUID + 3-byte payload.
            val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(UUID.fromString(SERVICE_UUID)), byteArrayOf(0x43, 0x41, 0x31)).build()
            _state.value = _state.value.copy(phase = gate.phase, message = "已提交广播请求，等待系统真实回调", result = "等待启动回调")
            handler.postDelayed(own.startupTimeout, 8_000)
            handler.postDelayed(own.timeout, TIMEOUT_MS)
            advertiser.startAdvertising(settings, data, own.callback)
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}（${if (e is SecurityException) "权限或系统策略拒绝" else "蓝牙调用异常，不能据此断定硬件不支持"}）"
            _state.value = _state.value.copy(message = detail, result = "启动调用抛异常", error = detail, phase = BleTestSession.Phase.FAILED)
            if (session != null) stop("启动异常后清理")
        }
    }

    private fun unavailable(reason: String) {
        _state.value = _state.value.copy(phase = BleTestSession.Phase.FAILED, message = reason, result = "未发起广播：$reason")
    }

    private fun removeTimers(own: Session) {
        handler.removeCallbacks(own.timeout)
        handler.removeCallbacks(own.startupTimeout)
    }

    private fun stopOwn(own: Session): String? = try {
        own.advertiser.stopAdvertising(own.callback)
        null
    } catch (e: Exception) { "${e.javaClass.simpleName}：停止调用异常，实际停止状态无法确认；系统广播超时仍设为 30 秒" }

    private fun finishStop(own: Session, failure: String?, reason: String) {
        gate.stopped(own.token, failure == null)
        if (failure == null) session = null
        _state.value = _state.value.copy(phase = gate.phase, message = if (failure == null) "$reason：已提交停止请求（系统无停止回调）" else "$reason：$failure；可重试停止，当前禁止新测试", cleanup = failure ?: "$reason；停止请求已提交，未验证无线信号是否消失")
    }

    private fun updateLateCleanup() {
        val blocked = retiredStops.isNotEmpty()
        _state.value = _state.value.copy(
            cleanupBlocked = blocked,
            lateCleanup = if (blocked) retiredStops.values.joinToString("；") else "旧会话停止请求已提交；系统无停止回调",
            message = if (blocked) "旧会话迟到回调清理异常，禁止新测试；请重试停止或在系统关闭蓝牙"
                else if (_state.value.cleanupBlocked) "旧会话清理已提交；当前状态见测试结果，系统无停止回调"
                else _state.value.message,
        )
    }

    fun stop(reason: String = "用户停止") {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { stop(reason) }; return }
        val own = session
        if (own == null && retiredStops.isEmpty()) return
        if (own != null) {
            gate.cancel(own.token) // Invalidate before issuing stop: callbacks must not resurrect the session.
            removeTimers(own)
            val failure = stopOwn(own)
            finishStop(own, failure, reason)
            if (failure != null && !released) {
                val remaining = own.deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) handler.postDelayed(own.timeout, remaining)
            }
        }
        if (retiredStops.isNotEmpty()) {
            retiredStops.keys.toList().forEach { retired ->
                handler.removeCallbacks(retired.lateRetry)
                val failure = stopOwn(retired)
                if (failure == null) retiredStops.remove(retired) else retiredStops[retired] = failure
            }
            updateLateCleanup()
            if (retiredStops.isEmpty() && session == null) {
                _state.value = _state.value.copy(message = "$reason：已提交本应用各会话停止请求（系统无停止回调）")
            }
        }
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { release() }; return }
        if (released) return
        released = true
        stop("页面所属 ViewModel 已释放")
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
