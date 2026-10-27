package com.carassistant.core

/**
 * BLE 方向诊断（纯逻辑，无 Android 依赖）。
 *
 * 本应用装在车机上（2024 款科鲁泽 MyLink / Android 10）。要回答的问题有两个，
 * 必须各自判定，不允许合并成一句「BLE 正常 / BLE 异常」：
 *
 * - 发送方向（车机 → 手机）：车机发出的广播，手机能不能扫到。
 *   依据只有两项：本应用广播启动回调 + 手机侧登记（用户自述）。
 * - 接收方向（设备 → 车机）：车机能不能收到手机 / 周围设备的广播。
 *   依据只有本机限时扫描结果。
 *
 * 车机自己在扫描时基本收不到自己刚发出的广播（同一蓝牙控制器），偶发捕获不可靠，
 * 所以本机扫描结果永远不能当作发送方向的证据。
 */
object BleDirection {
    const val SELF_SCAN_SCOPE = "本应用运行在车机上：本机扫描只验证接收方向（车机能否收到手机或周围设备的广播），不能证明本机广播被手机看到。车机和手机共用同一套蓝牙控制器，自己扫描时基本收不到自己刚发出的广播，偶发捕获不可靠、不能作为证据；同时广播 + 扫描还可能降低广播成功率，建议两件事分开做。"
    const val PHONE_SIDE_DISCLAIMER = "手机侧登记是用户自述，不是系统自动采集：本应用没有连接手机、没有读取手机日志、也没有验证手机侧结果，不会把它升级为客观事实。"
    const val LIMITS = "方向结论只依据本应用的回调记录和用户登记的最少信息：未登记即未知，不用推测补齐；本机扫描无结果不代表周围没有广播。手机侧结果需用户在手机上自行用扫描工具确认，本应用无法自动核实。默认导出不含 MAC / VIN / 账号，备注按不可信文本处理（纯文本、限长、不执行）。"

    /** 主流手机型号不预填：车机无从得知，留空由用户填写，避免伪造证据。 */
    enum class PhoneOutcome(val label: String) {
        UNTESTED("未测试"),
        SEEN("扫到"),
        NOT_SEEN("没扫到"),
        WRONG_PAYLOAD("扫到但载荷不是 CA1"),
    }

    /** 本应用广播测试的状态（来自真实回调，不是我们的猜测）。 */
    enum class AdvertiseState(val label: String) {
        NOT_TESTED("本次未做本应用广播测试"),
        STARTED("本应用有广播启动成功回调（仅表示系统受理）"),
        NOT_STARTED("本应用本次没有启动成功的广播记录"),
    }

    enum class SendState(val label: String) {
        SEEN("已扫到（手机侧自述）"),
        NOT_SEEN("未扫到（手机侧自述，且本机启动回调成功）"),
        PAYLOAD_MISMATCH("扫到但载荷不是 CA1（手机侧自述）"),
        UNKNOWN("未知"),
    }

    enum class ReceiveState(val label: String) {
        OBSERVED("已收到广播（本机扫描，仅证明接收方向）"),
        NOT_OBSERVED("未观察到（本机扫描已完成；不能据此判定周围没有广播）"),
        UNKNOWN("未知"),
    }

    /**
     * 用户对手机侧的登记。全部字段都是自由文本，按不可信输入处理：
     * 去掉控制字符、折行压平、按字段限长；默认导出再做脱敏。
     */
    data class PhoneObservation(
        val outcome: PhoneOutcome = PhoneOutcome.UNTESTED,
        val model: String = "",
        val tool: String = "",
        val observedAt: String = "",
        val note: String = "",
    ) {
        companion object {
            const val MAX_MODEL = 40
            const val MAX_TOOL = 40
            const val MAX_TIME = 40
            const val MAX_NOTE = 200
            private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
            private val BREAK = Regex("[\\r\\n\\t\\u2028\\u2029]+")
            /** 单行化 + 去控制字符 + 限长。保留其余原样，不解释、不执行。 */
            fun text(raw: String, limit: Int): String = BREAK.replace(CONTROL.replace(raw, ""), " ").trim().take(limit)
            fun of(
                outcome: PhoneOutcome,
                model: String = "",
                tool: String = "",
                observedAt: String = "",
                note: String = "",
            ) = PhoneObservation(
                outcome = outcome,
                model = text(model, MAX_MODEL),
                tool = text(tool, MAX_TOOL),
                observedAt = text(observedAt, MAX_TIME),
                note = text(note, MAX_NOTE),
            )
        }

        val recorded get() = outcome != PhoneOutcome.UNTESTED
    }

    data class Reason(val basis: String, val missing: String = "", val notes: List<String> = emptyList())

    data class Verdict(
        val send: SendState,
        val sendReason: Reason,
        val receive: ReceiveState,
        val receiveReason: Reason,
    ) {
        fun lines(): List<String> = buildList {
            line("发送方向（车机→手机）", send.label, sendReason)
            line("接收方向（设备→车机）", receive.label, receiveReason)
            add("两个方向分别判定，不合并为「BLE 正常 / BLE 异常」；缺失证据一律记为未知。")
        }

        private fun MutableList<String>.line(title: String, label: String, reason: Reason) {
            add("$title：$label")
            add("  依据：${reason.basis}")
            if (reason.missing.isNotBlank()) add("  缺失：${reason.missing}")
            reason.notes.forEach { add("  注意：$it") }
        }
    }

    /** 本机扫描结果（只用于接收方向）。attempted 表示用户已发起过扫描，不代表启动成功。 */
    data class OwnScan(val attempted: Boolean = false, val rows: Int = 0, val message: String = "")

    /** 只在广播启动回调成功之后才承认「本机在广播」，不把提交请求当成成功。 */
    fun advertiseState(phase: BleTestSession.Phase): AdvertiseState = when (phase) {
        BleTestSession.Phase.ADVERTISING, BleTestSession.Phase.STOPPED, BleTestSession.Phase.STOP_FAILED -> AdvertiseState.STARTED
        BleTestSession.Phase.STARTING, BleTestSession.Phase.FAILED -> AdvertiseState.NOT_STARTED
        BleTestSession.Phase.IDLE -> AdvertiseState.NOT_TESTED
    }

    fun verdict(
        advertise: AdvertiseState,
        observation: PhoneObservation,
        scan: OwnScan = OwnScan(),
    ): Verdict {
        val sendNotes = mutableListOf<String>()
        val send: SendState
        val sendBasis: String
        val sendMissing: String
        when (observation.outcome) {
            PhoneOutcome.UNTESTED -> {
                send = SendState.UNKNOWN
                sendBasis = "${advertise.label}；手机侧登记：未测试"
                sendMissing = "手机侧「扫到 / 没扫到」没有登记，发送方向无法判定"
            }
            PhoneOutcome.SEEN -> {
                send = SendState.SEEN
                sendBasis = "手机侧登记「扫到」+ ${advertise.label}"
                sendMissing = ""
                if (advertise != AdvertiseState.STARTED) {
                    sendNotes += "本机没有对应的启动成功回调，与手机侧「扫到」对不上；可能来自更早的广播或另一个应用，请重跑一次 30 秒广播测试再做结论"
                }
            }
            PhoneOutcome.NOT_SEEN -> {
                if (advertise == AdvertiseState.STARTED) {
                    send = SendState.NOT_SEEN
                    sendBasis = "手机侧登记「没扫到」+ ${advertise.label}"
                    sendMissing = ""
                    sendNotes += "启动回调只代表系统受理了请求，不保证无线信号真的发出去；手机没扫到不等于车机没在广播"
                } else {
                    send = SendState.UNKNOWN
                    sendBasis = "手机侧登记「没扫到」，${advertise.label}"
                    sendMissing = "缺少本机广播启动成功的记录，「没扫到」不能归因到车机的广播"
                }
            }
            PhoneOutcome.WRONG_PAYLOAD -> {
                send = SendState.PAYLOAD_MISMATCH
                sendBasis = "手机侧登记「扫到但载荷不是 CA1」+ ${advertise.label}"
                sendMissing = ""
                sendNotes += "载荷不是 CA1（43 41 31）时不能算本应用测试广播被扫到；请在手机上核对 Service Data UUID 与载荷"
            }
        }

        val receiveNotes = mutableListOf<String>()
        val receive: ReceiveState
        val receiveBasis: String
        val receiveMissing: String
        when {
            !scan.attempted -> {
                receive = ReceiveState.UNKNOWN
                receiveBasis = "本次没有做本机限时扫描"
                receiveMissing = "接收方向没有扫描结果，不做推测"
            }
            scan.rows > 0 -> {
                receive = ReceiveState.OBSERVED
                receiveBasis = "本机限时扫描收到 ${scan.rows} 条广播记录；只说明车机收到了广播，不区分是否来自手机，也不涉及手机能否收到车机"
                receiveMissing = ""
            }
            else -> {
                receive = ReceiveState.NOT_OBSERVED
                val note = scan.message.trim().take(160)
                receiveBasis = "本机限时扫描已结束但没有结果" + if (note.isNotBlank()) "（$note）" else ""
                receiveMissing = ""
                receiveNotes += "无结果可能来自权限 / 定位 / 系统节流 / 无线环境，不能据此判定周围没有广播"
            }
        }

        return Verdict(
            send = send,
            sendReason = Reason(sendBasis, sendMissing, sendNotes),
            receive = receive,
            receiveReason = Reason(receiveBasis, receiveMissing, receiveNotes),
        )
    }

    // 默认导出脱敏：MAC、邮箱、疑似 VIN。备注是自由文本，不能让用户无意中带出身份标识。
    private val MAC = Regex("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val VIN_TOKEN = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")

    fun redact(raw: String): String {
        val noMac = MAC.replace(raw, "[已隐藏 MAC]")
        val noMail = EMAIL.replace(noMac, "[已隐藏邮箱]")
        return VIN_TOKEN.replace(noMail) { match ->
            val token = match.value
            // VIN 一定含数字且以字母为主；纯数字或纯字母的长串不是 VIN，别误伤。
            if (token.count { it.isDigit() } >= 3 && token.count { it.isLetter() } >= 5) "[已隐藏疑似 VIN]" else token
        }
    }

    /** 登记内容的默认（脱敏）导出行。备注原样不可信，仅做单行化与脱敏。 */
    fun exportLines(observation: PhoneObservation): List<String> = listOf(
        "结果：${observation.outcome.label}",
        "手机型号：${observation.model.ifBlank { "未填写" }}",
        "扫描工具：${observation.tool.ifBlank { "未填写" }}",
        "观察时间：${observation.observedAt.ifBlank { "未填写" }}",
        "备注：${observation.note.ifBlank { "未填写" }}",
    ).map { redact(it) }

    fun reportFields(
        advertise: AdvertiseState,
        observation: PhoneObservation,
        scan: OwnScan = OwnScan(),
    ): List<CarDiagnostics.Field> {
        val verdict = verdict(advertise, observation, scan)
        fun basis(reason: Reason): String {
            val missing = if (reason.missing.isNotBlank()) "；缺失：${reason.missing}" else ""
            val notes = reason.notes.joinToString("") { "；注意：$it" }
            return reason.basis + missing + notes
        }
        return listOf(
            CarDiagnostics.Field("BLE 发送方向（车机→手机）", verdict.send.label, "依据：本应用广播启动回调 + 手机侧用户自述登记；两方向不合并"),
            CarDiagnostics.Field("BLE 发送方向依据", basis(verdict.sendReason), "本应用状态记录 + 用户登记，非系统自动采集"),
            CarDiagnostics.Field("BLE 接收方向（设备→车机）", verdict.receive.label, "依据：本机限时扫描结果；不证明手机能扫到车机"),
            CarDiagnostics.Field("BLE 接收方向依据", basis(verdict.receiveReason), "本机扫描状态与条数，未读取对端身份"),
            CarDiagnostics.Field("手机侧观察登记（用户自述）", exportLines(observation).joinToString("；"), PHONE_SIDE_DISCLAIMER),
            CarDiagnostics.Field("BLE 方向诊断限制", LIMITS, "本应用测试范围"),
        )
    }

    /** 报告正文里的方向章节；复用既有 TXT / 局域网页导出通道，不新造体系。 */
    fun report(
        advertise: AdvertiseState,
        observation: PhoneObservation,
        scan: OwnScan = OwnScan(),
    ): String = buildString {
        appendLine("BLE 方向诊断（发送 / 接收分开判定）")
        verdict(advertise, observation, scan).lines().forEach { appendLine(it) }
        appendLine("手机侧登记（用户自述，非系统自动采集）")
        exportLines(observation).forEach { appendLine("  $it") }
        appendLine(SELF_SCAN_SCOPE)
        append(LIMITS)
    }
}
