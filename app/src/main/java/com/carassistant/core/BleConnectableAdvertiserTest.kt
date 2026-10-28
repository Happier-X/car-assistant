package com.carassistant.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseData
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

/**
 * 第二种 BLE 广播测试模式：**可连接 / 可扫描广播**（`startAdvertisingSet`）。
 *
 * 与第一种模式（[BleAdvertiserTest]，`startAdvertising` + connectable=false）**并存且互不干扰**：
 * 各自独立的 [BleTestSession] 门禁、独立的 Snapshot、独立的 30 秒时限与停止清理，
 * 因此两种模式可以分别跑，任何一方的结果都不会污染另一方。
 *
 * 只做手动的、有时限的广播。不扫描、不连接、不自动开蓝牙、不读本机名称/MAC，
 * 不改 CarLink、不动 RSSI、不改信道、不反射修改系统私有字段。
 */
@SuppressLint("MissingPermission") // targetSdk28 旧权限 + 下方真实 SecurityException 处理
class BleConnectableAdvertiserTest(private val context: Context) {
    companion object {
        const val TIMEOUT_MS = 30_000L
        const val STARTUP_TIMEOUT_MS = 8_000L
        const val STOP_CONFIRM_MS = 5_000L
        private const val MAX_EVENTS = 48
        const val LIMITS = BleConnectableAdv.LIMITS

        /** 每个来源单独隔离：取不到就是「未知」，绝不写成「不支持」。 */
        fun capabilityFields(context: Context): List<CarDiagnostics.Field> {
            val result = mutableListOf<CarDiagnostics.Field>()
            fun adapter(): BluetoothAdapter? =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            fun read(label: String, source: String, block: () -> String) {
                val value = try {
                    block()
                } catch (e: SecurityException) {
                    "无权限 / 系统拒绝（${e.javaClass.simpleName}）"
                } catch (e: Exception) {
                    "不可读取（${e.javaClass.simpleName}）"
                }
                result += CarDiagnostics.Field(label, value, source)
            }
            read("可连接广播·多实例广播能力", "BluetoothAdapter.isMultipleAdvertisementSupported（API26+）") {
                val a = adapter() ?: return@read "未知：无适配器"
                if (!a.isEnabled) "蓝牙关闭，暂不判断"
                else if (a.isMultipleAdvertisementSupported) "支持（系统能力报告）"
                else "不支持（系统能力报告）"
            }
            read("可连接广播·广播接口", "BluetoothAdapter.bluetoothLeAdvertiser") {
                val a = adapter() ?: return@read "未知：无适配器"
                if (!a.isEnabled) "不可用：蓝牙已关闭"
                else if (a.bluetoothLeAdvertiser == null) "不可用：系统未提供广播接口（不支持或厂商限制）"
                else "可取得接口；能否启动以真实回调为准"
            }
            read("可连接广播·LE 扩展广播能力", "BluetoothAdapter.isLeExtendedAdvertisingSupported（startAdvertisingSet 依赖它）") {
                val a = adapter() ?: return@read "未知：无适配器"
                if (!a.isEnabled) "蓝牙关闭，暂不判断"
                else try {
                    if (a.isLeExtendedAdvertisingSupported) "支持（系统能力报告）"
                    else "不支持（系统能力报告）；startAdvertisingSet 很可能失败"
                } catch (e: Exception) {
                    "未知（${e.javaClass.simpleName}）"
                }
            }
            read("可连接广播·适配器状态", "BluetoothAdapter.getState()") {
                val a = adapter() ?: return@read "未知：无适配器"
                try {
                    "${stateName(a.state)}（数值 ${a.state}）"
                } catch (e: Exception) {
                    "未知（${e.javaClass.simpleName}）"
                }
            }
            read("可连接广播·蓝牙开关", "BluetoothAdapter.isEnabled") {
                val a = adapter() ?: return@read "未知：无适配器"
                if (a.isEnabled) "已开启" else "已关闭"
            }
            return result
        }

        private fun stateName(state: Int): String = when (state) {
            BluetoothAdapter.STATE_OFF -> "STATE_OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
            BluetoothAdapter.STATE_ON -> "STATE_ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
            else -> "未知状态"
        }

        /** 界面/报告里给用户看的 8 秒/30 秒/停止确认口径。 */
        const val TIMING_NOTE =
            "点击后提交 startAdvertisingSet 请求，同时向系统申请 30 秒 duration 作为兜底；" +
                "8 秒内没有 onAdvertisingSetStarted 记为「回调超时」，不算成功；" +
                "停止走 stopAdvertisingSet + onAdvertisingSetStopped，$STOP_CONFIRM_MS 毫秒内没有停止回调会按「未确认」记录。"
    }

    data class Snapshot(
        val phase: BleTestSession.Phase = BleTestSession.Phase.IDLE,
        val startedStatus: Int? = null,
        val timedOut: Boolean = false,
        val message: String = "尚未进行可连接广播测试",
        val time: String = "未测试",
        val result: String = "未测试",
        val error: String = "无",
        val cleanup: String = "无",
        val events: List<String> = emptyList(),
        val actualParams: String = "未取得（尚未收到 onAdvertisingSetStarted）",
        val receiver: String = "已注册蓝牙开关监听",
        val cleanupBlocked: Boolean = false,
    ) {
        val mode: BleConnectableAdv.ModeState
            get() = BleConnectableAdv.modeState(phase, startedStatus, timedOut)

        val busy get() = cleanupBlocked || phase == BleTestSession.Phase.STARTING ||
            phase == BleTestSession.Phase.ADVERTISING || phase == BleTestSession.Phase.STOP_FAILED

        /**
         * 报告字段（表格化，一眼判读）。
         * [plain] 是对照模式（不可连接广播）的既有证据，只用于组合结论，不参与本模式自身判定。
         * [capability] 由外部传入（能力项需 Context，Snapshot 本身不持 Context）。
         */
        fun reportFields(
            plain: BleDirection.AdvertiseState,
            capability: List<CarDiagnostics.Field> = emptyList(),
        ): List<CarDiagnostics.Field> {
            val mode = mode
            val explanation = BleConnectableAdv.explain(mode)
            val combination = BleConnectableAdv.combination(plain, mode)
            return listOf(
                CarDiagnostics.Field("可连接广播·模式结论", mode.label, "本应用 startAdvertisingSet 真实回调/状态码，非推测"),
                CarDiagnostics.Field("可连接广播·归因", "${explanation.blame.label}；${explanation.hint}", "状态码归类；DATA_TOO_LARGE 与 TOO_MANY_ADVERTISERS 不算能力失败"),
                CarDiagnostics.Field("可连接广播·参数（请求值）", BleConnectableAdv.PARAMETERS, "本应用 AdvertisingSetParameters.Builder 设置值；未反射改系统字段"),
                CarDiagnostics.Field("可连接广播·参数（系统/实际）", actualParams, "AdvertisingSetParameters.toString() 或系统回调带来的实际值"),
                CarDiagnostics.Field("可连接广播·广播内容", BleConnectableAdv.exportLine(), "默认导出隐藏载荷/标签字节，只给结构"),
                CarDiagnostics.Field("可连接广播·31 字节占用", BleConnectableAdv.budgetLine(), "legacy 广播数据与扫描响应各自 ≤31 字节，实算"),
                CarDiagnostics.Field("可连接广播·最近测试", "$time；结果：$result；错误：$error", "本应用用户手动触发，不代表当前仍在广播"),
                CarDiagnostics.Field("可连接广播·回调记录", if (events.isEmpty()) "无" else events.joinToString("\n"), "AdvertisingSetCallback 全部回调，均带时间戳"),
                CarDiagnostics.Field("可连接广播·停止记录", cleanup, "只停本应用这个 callback/广播集；系统停止有回调，未确认会写明"),
                CarDiagnostics.Field("可连接广播·能力", if (capability.isEmpty()) "未知：本次未采集能力项" else capability.joinToString("；") { "${it.label}=${it.value}" }, "系统能力报告；取不到写未知"),
                CarDiagnostics.Field("两种广播模式对照", combination.lines().joinToString("\n"), "对照模式=startAdvertising(不可连接)，本模式=startAdvertisingSet(可连接+可扫描)"),
                CarDiagnostics.Field("可连接广播·限制", LIMITS, "本应用测试范围"),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val gate = BleTestSession()
    private val _state = MutableStateFlow(Snapshot())
    val state = _state.asStateFlow()
    private var released = false
    private var registered = false
    private var session: Session? = null
    /** 已经结束、但可能还有迟到回调的会话；停止请求异常时阻塞新测试（与对照模式同样的门禁）。 */
    private val retiredStops = mutableMapOf<Session, String>()

    private inner class Session(
        val token: Long,
        val advertiser: BluetoothLeAdvertiser,
        /** 我们提交给系统的参数对象；系统没有 getter 暴露「实际生效值」，只能记录请求值 + toString()。 */
        val params: AdvertisingSetParameters,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + TIMEOUT_MS
        val timeout = Runnable {
            if (session === this) stop("30 秒测试时限已到")
        }
        val stopTimeout = Runnable {
            if (session === this && gate.phase == BleTestSession.Phase.STOPPED) {
                // 停止请求已经受理但系统没回调：按「未确认」收尾，允许重测。
                gate.stopped(token, true)
                session = null
                record(this, "停止回调超时", "${STOP_CONFIRM_MS}ms 内未收到 onAdvertisingSetStopped；按停止请求已提交处理，未确认无线信号是否消失")
                _state.value = _state.value.copy(
                    phase = gate.phase,
                    cleanup = "已提交停止请求；$STOP_CONFIRM_MS 毫秒内无 onAdvertisingSetStopped（未确认）",
                    message = "测试已结束；系统未回调停止确认，需要时可重跑",
                )
            }
        }
        val startupTimeout = Runnable {
            if (session === this && _state.value.phase == BleTestSession.Phase.STARTING) {
                record(this, "启动回调超时", "${STARTUP_TIMEOUT_MS}ms 内未收到 onAdvertisingSetStarted → 记为回调超时，不算成功")
                gate.failure(token)
                session = null
                // 没有 AdvertisingSet 句柄：仍然提交一次停止请求（系统会忽略未启动的 callback），失败则阻塞新测试。
                val failure = stopOwn(this)
                if (failure == null) retiredStops.remove(this) else retiredStops[this] = failure
                _state.value = _state.value.copy(
                    phase = gate.phase,
                    timedOut = true,
                    message = "启动回调超时：8 秒内没有 onAdvertisingSetStarted，本次不算成功，也不能算车机不支持",
                    result = "启动回调超时（不算成功）",
                    error = "8 秒内未收到 onAdvertisingSetStarted",
                )
                updateLateCleanup()
            }
        }

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                handler.post {
                    if (released) return@post
                    record(
                        this@Session, "onAdvertisingSetStarted",
                        "status=$status（${BleConnectableAdv.statusName(status)}），txPower=${txPower}dBm，" +
                            "set=${if (advertisingSet == null) "null" else "已取得"}",
                    )
                    val own = this@Session
                    val current = session === own
                    if (current && gate.success(own.token)) {
                        handler.removeCallbacks(own.startupTimeout)
                        _state.value = _state.value.copy(
                            phase = gate.phase,
                            startedStatus = status,
                            timedOut = false,
                            actualParams = "txPower=${txPower}dBm；AdvertisingSetParameters.toString()=${paramsText(own)}",
                            message = "系统回调启动成功（status=0）；最长 30 秒，仍需另一设备实扫",
                            result = "onAdvertisingSetStarted status=0 @ ${eventTime(own)}",
                            error = "无",
                        )
                        return@post
                    }
                    if (current && _state.value.phase == BleTestSession.Phase.ADVERTISING) {
                        // 当前会话重复回调：无害，只记录。
                        return@post
                    }
                    if (current && gate.failure(own.token)) {
                        // 真实启动失败：保留数字状态码，按归类给出归因（数据/环境/能力/系统/未知）。
                        handler.removeCallbacks(own.startupTimeout)
                        handler.removeCallbacks(own.timeout)
                        session = null
                        val detail = "status=$status（${BleConnectableAdv.statusName(status)}）@ ${eventTime(own)}"
                        _state.value = _state.value.copy(
                            phase = gate.phase,
                            startedStatus = status,
                            timedOut = false,
                            result = "onAdvertisingSetStarted 失败：$detail",
                            error = detail,
                            message = "系统回调启动失败：${BleConnectableAdv.classify(status).label}",
                        )
                        // 失败也要提交一次停止请求，避免在系统侧留下未清理的广播集。
                        val failureOnFailure = stopOwn(own)
                        if (failureOnFailure != null) {
                            retiredStops[own] = failureOnFailure
                            updateLateCleanup()
                        }
                        return@post
                    }
                    // 迟到的成功回调：本会话已经不成立（超时/已取消）。只停这一个 callback。
                    record(own, "迟到回调清理", "本会话已结束，立即停止这次意外启动的广播集")
                    if (current) {
                        handler.removeCallbacks(own.timeout)
                        handler.removeCallbacks(own.startupTimeout)
                        gate.failure(own.token)
                        session = null
                        _state.value = _state.value.copy(
                            phase = gate.phase,
                            timedOut = true,
                            startedStatus = status,
                            result = "迟到回调（已结束的会话）",
                            error = "会话已结束后才收到启动回调，结果不作数",
                        )
                    }
                    val failure = stopOwn(own)
                    if (failure == null) retiredStops.remove(own) else retiredStops[own] = failure
                    updateLateCleanup()
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                handler.post {
                    if (released) return@post
                    val own = session
                    record(own, "onAdvertisingSetStopped", "系统已停止本应用这个广播集")
                    if (own == null) {
                        updateLateCleanup()
                        return@post
                    }
                    handler.removeCallbacks(own.stopTimeout)
                    handler.removeCallbacks(own.timeout)
                    if (gate.phase == BleTestSession.Phase.ADVERTISING) {
                        // 系统自己停的（duration 到期 / 蓝牙关闭 / 厂商策略）：先取消再落停止态。
                        gate.cancel(own.token)
                    }
                    gate.stopped(own.token, true)
                    session = null
                    _state.value = _state.value.copy(
                        phase = gate.phase,
                        cleanup = "onAdvertisingSetStopped 已回调（系统已停止本应用广播集）",
                        message = if (_state.value.result.startsWith("onAdvertisingSetStarted"))
                            "测试结束：系统已确认停止本应用的广播集" else _state.value.message,
                    )
                }
            }

            override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
                handler.post {
                    if (released) return@post
                    val own = session
                    record(own, "onAdvertisingEnabled", "enable=$enable，status=$status（${BleConnectableAdv.statusName(status)}）")
                    if (!enable && own != null && gate.phase == BleTestSession.Phase.ADVERTISING) {
                        handler.removeCallbacks(own.timeout)
                        gate.cancel(own.token)
                        gate.stopped(own.token, true)
                        session = null
                        _state.value = _state.value.copy(
                            phase = gate.phase,
                            cleanup = "onAdvertisingEnabled 报告广播已停用（enable=false），未等待 stopped 回调",
                            error = if (status == 0) _state.value.error else "onAdvertisingEnabled status=$status（${BleConnectableAdv.statusName(status)}）",
                            message = "系统回调广播已停用（enable=false）；本次结果见上方",
                        )
                    }
                }
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                handler.post {
                    if (released) return@post
                    record(session, "onAdvertisingDataSet", "status=$status（${BleConnectableAdv.statusName(status)}）")
                    noteDataStatus("onAdvertisingDataSet", status)
                }
            }

            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                handler.post {
                    if (released) return@post
                    record(session, "onScanResponseDataSet", "status=$status（${BleConnectableAdv.statusName(status)}）")
                    noteDataStatus("onScanResponseDataSet", status)
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON
            ) {
                stop("蓝牙关闭或正在切换，停止本次可连接广播测试")
            }
        }
    }

    init {
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        } catch (e: Exception) {
            _state.value = _state.value.copy(receiver = "开关监听不可用（${e.javaClass.simpleName}）；仍有 8 秒/30 秒超时与离页清理")
        }
    }

    /** 所有控制器操作与回调都在主线程串行执行。 */
    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { start() }; return }
        if (released || session != null || retiredStops.isNotEmpty()) return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        _state.value = Snapshot(
            phase = _state.value.phase,
            receiver = _state.value.receiver,
            time = time,
            message = "准备可连接广播测试",
            result = "准备测试",
        )
        try {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                unavailable("系统未声明 FEATURE_BLUETOOTH_LE，未启动测试"); return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED
            ) {
                unavailable("无蓝牙读取/管理权限，请检查应用权限或系统限制"); return
            }
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null) { unavailable("系统未提供蓝牙适配器"); return }
            if (!adapter.isEnabled) { unavailable("蓝牙已关闭，请先请求系统开启或在蓝牙设置中开启"); return }
            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) { unavailable("系统未提供 BLE 广播接口，不支持或存在厂商限制"); return }
            // 与 CarLink 对齐：可连接 + 可扫描 + legacy + LE_1M + MEDIUM 发射功率。
            // 不调用 setInterval（用系统默认，合法区间 160~16777215），不反射改 mInterval。
            val params = AdvertisingSetParameters.Builder()
                .setConnectable(true)
                .setScannable(true)
                .setLegacyMode(true)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()
            val token = gate.begin() ?: return
            val own = Session(token, advertiser, params)
            session = own
            // 广播数据：系统 Flags + 128 位 Service Data（2 + 16 + 3 = 21）= 24 字节 ≤ 31。
            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(UUID.fromString(BleConnectableAdv.SERVICE_UUID)), BleConnectableAdv.PAYLOAD)
                .build()
            // 扫描响应：AD 头 2 + 公司 ID 2 + 固定标签 3 = 7 字节 ≤ 31（两包合计 31，保守口径也不超）。
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(
                    BleConnectableAdv.TEST_COMPANY_ID,
                    BleConnectableAdv.SCAN_RSP_TAG.toByteArray(Charsets.US_ASCII),
                )
                .build()
            _state.value = _state.value.copy(
                phase = gate.phase,
                startedStatus = null,
                timedOut = false,
                message = "已提交 startAdvertisingSet 请求，等待系统真实回调（8 秒内无回调记为超时）",
                result = "等待 onAdvertisingSetStarted",
                error = "无",
                cleanup = "无",
                actualParams = "未取得（等待 onAdvertisingSetStarted）",
                events = emptyList(),
            )
            record(own, "startAdvertisingSet 请求", BleConnectableAdv.PARAMETERS)
            handler.postDelayed(own.startupTimeout, STARTUP_TIMEOUT_MS)
            handler.postDelayed(own.timeout, TIMEOUT_MS)
            advertiser.startAdvertisingSet(params, advertiseData, scanResponse, null, null, TIMEOUT_MS.toInt(), 0, own.callback, handler)
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName} @ ${eventTime(session)}（${if (e is SecurityException) "权限或系统策略拒绝" else "蓝牙调用异常，不能据此断定硬件不支持"}）"
            val own = session
            if (own != null) {
                gate.failure(own.token)
                handler.removeCallbacks(own.startupTimeout)
                handler.removeCallbacks(own.timeout)
                session = null
            }
            _state.value = _state.value.copy(
                phase = if (own == null) BleTestSession.Phase.FAILED else gate.phase,
                message = detail,
                result = "启动调用抛异常",
                error = detail,
            )
        }
    }

    private fun unavailable(reason: String) {
        _state.value = _state.value.copy(phase = BleTestSession.Phase.FAILED, message = reason, result = "未发起测试：$reason")
    }

    private fun paramsText(own: Session): String = runCatching { own.params.toString() }
        .getOrElse { "不可读取（${it.javaClass.simpleName}）" }

    private fun noteDataStatus(name: String, status: Int) {
        if (status == 0) return
        val detail = "$name status=$status（${BleConnectableAdv.statusName(status)}）"
        _state.value = _state.value.copy(
            error = detail,
            result = "$name 失败：status=$status",
            message = "广播内容设置失败：手机侧可能扫不到本模式载荷，请重跑一次（本次不算能力结论）",
        )
    }

    private fun eventTime(own: Session?): String {
        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val offset = own?.let { "+${SystemClock.elapsedRealtime() - it.startedAt}ms" } ?: ""
        return if (offset.isEmpty()) wall else "$wall $offset"
    }

    private fun record(own: Session?, name: String, detail: String) {
        val line = "${eventTime(own)} $name：$detail"
        val events = (_state.value.events + line).takeLast(MAX_EVENTS)
        _state.value = _state.value.copy(events = events)
    }

    private fun stopOwn(own: Session): String? = try {
        own.advertiser.stopAdvertisingSet(own.callback)
        null
    } catch (e: Exception) {
        "${e.javaClass.simpleName}：stopAdvertisingSet 调用异常，实际停止状态无法确认"
    }

    private fun updateLateCleanup() {
        val blocked = retiredStops.isNotEmpty()
        _state.value = _state.value.copy(
            cleanupBlocked = blocked,
            cleanup = if (blocked) "停止调用异常：${retiredStops.values.joinToString("；")}"
            else "本应用这个 callback/广播集的停止请求已提交",
            message = if (blocked) "停止调用异常，已禁止新测试；请重试停止，或到系统里关闭蓝牙后再试"
            else if (_state.value.cleanupBlocked) "停止请求已重试提交；系统无停止确认，结果见上方" else _state.value.message,
        )
    }

    fun stop(reason: String = "用户停止") {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { stop(reason) }; return }
        val own = session
        if (own == null) {
            if (retiredStops.isNotEmpty()) retryRetired(reason)
            return
        }
        gate.cancel(own.token) // 先作废会话，回调不能再把它复活
        handler.removeCallbacks(own.startupTimeout)
        handler.removeCallbacks(own.timeout)
        record(own, "停止请求", reason)
        val failure = stopOwn(own)
        if (failure != null) {
            gate.stopped(own.token, false)
            session = null
            retiredStops[own] = failure
            updateLateCleanup()
            return
        }
        _state.value = _state.value.copy(
            phase = gate.phase,
            message = "$reason：已提交停止请求，等待 onAdvertisingSetStopped",
            cleanup = "$reason：已提交停止请求，等待系统停止回调",
        )
        handler.postDelayed(own.stopTimeout, STOP_CONFIRM_MS)
    }

    private fun retryRetired(reason: String) {
        retiredStops.keys.toList().forEach { retired ->
            val failure = stopOwn(retired)
            if (failure == null) retiredStops.remove(retired) else retiredStops[retired] = failure
        }
        updateLateCleanup()
        if (retiredStops.isEmpty()) {
            _state.value = _state.value.copy(message = "$reason：旧会话停止请求已提交（系统无停止确认）")
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
