package com.carassistant.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Conservative recognizers for the observed CarLink log vocabulary. No inferred transitions. */
object ConnectionEvidence {
    const val MAX_BYTES = 512 * 1024
    const val MAX_LINES = 12000
    const val MAX_EVENTS = 500
    const val LIMITS = "按文件原始行序保留多次尝试，不跨来源对齐时钟。无年份/跨天/时区不同可能无法比较绝对时间。未见证据不等于未发生；回调成功不证明手机可见。共有异常不能直接判为根因。仅识别已验证的 CarLink 日志关键词；其他格式（含未验证的 nRF JSON）保留本地原文，不自动解释。"
    enum class Stage(val label: String) {
        BROADCAST("广播启动回调"), REQUEST("手机请求"), GROUP("P2P 建组请求受理"),
        NETWORK("客户端入网"), AUTH("认证交互"), CAST("投屏成功"), DISCONNECT("断开请求/事件")
    }
    data class Event(val stage: Stage, val line: Int, val time: String, val confidence: String, val evidence: String)
    data class Report(val text: String, val events: List<Event>, val anomalies: Map<String, List<Int>>, val capped: Boolean) {
        fun summary(source: String): String = buildString {
            appendLine("来源：$source；${events.size} 条阶段证据${if (capped) "（达到上限，后续未解析）" else ""}")
            if (events.isEmpty()) appendLine("无法自动解析连接阶段；请在本地查看原文。")
            events.forEach { appendLine("$source L${it.line} ${it.time} · ${it.stage.label} · ${it.confidence}") }
            Stage.entries.forEach { s -> if (events.none { it.stage == s }) appendLine("${s.label}：未知（未见匹配证据）") }
            anomalies.forEach { (key, lines) -> appendLine("异常线索 $key：L${lines.joinToString(",")}") }
        }
    }
    fun read(input: InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (buffer.size() <= MAX_BYTES) {
            val count = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - buffer.size()))
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                buffer.write(single)
                continue
            }
            buffer.write(chunk, 0, count)
        }
        val bytes = buffer.toByteArray()
        require(bytes.size <= MAX_BYTES) { "文件超过 512 KiB，未导入" }
        val (charset, offset) = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
            else -> Charsets.UTF_8 to 0
        }
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        require(text.none { it == '\u0000' }) { "包含 NUL 或未知编码；请另存为 UTF-8 文本" }
        require(text.count { it == '\n' } < MAX_LINES) { "文件超过 12000 行，未导入" }
        require(text.lineSequence().all { it.length <= 8192 }) { "单行超过 8192 字符，未导入" }
        return text
    }
    private val timestamp = Regex("^(?:\\d{4}-)?\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?")
    fun parse(text: String): Report {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文本过大" }
        require(text.lineSequence().count() <= MAX_LINES) { "行数过多" }
        val events = mutableListOf<Event>()
        val anomalies = linkedMapOf<String, MutableList<Int>>()
        var capped = false
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.take(8192)
            // Require a timestamp and a log tag: report headers and pasted prose are not log evidence.
            val time = timestamp.find(line)?.value ?: return@forEachIndexed
            if (!Regex(" [VDIWEF]/[A-Za-z_]").containsMatchIn(line)) return@forEachIndexed
            val stage = when {
                "ShareLink-BleObserver" in line && Regex("onSuccess:\\s*1\\b").containsMatchIn(line) -> Stage.BROADCAST
                "UCar_ShareLink(" in line && Regex("expectConnectType\\s*=\\s*\\d+").containsMatchIn(line) -> Stage.REQUEST
                "UCar_WifiP2pService" in line && "Create group onSuccess." in line -> Stage.GROUP
                "UCar_WifiP2pService" in line && "hasValidClient=true" in line -> Stage.NETWORK
                "UCar_ShareLink(" in line && "onClientKeyNegotiationReceived" in line -> Stage.AUTH
                "I/CarlinkHome(" in line && "cast connected, set uiState=4" in line -> Stage.CAST
                "UCar_ShareLink(" in line && (line.endsWith("Disconnect.") || "wifi onDisconnected" in line) -> Stage.DISCONNECT
                else -> null
            }
            if (stage != null) {
                if (events.size < MAX_EVENTS) events += Event(stage, index + 1, time,
                    if (stage == Stage.AUTH) "中：仅认证交互，不代表认证完成" else "高：明确日志事件，非端到端保证", line)
                else capped = true
            }
            val anomaly = when {
                "mInterval" in line && "denied" in line -> "mInterval 被拒"
                "BluetoothLeService.startAdvertising(" in line -> "startAdvertising 栈片段（异常头未知）"
                else -> null
            }
            if (anomaly != null) {
                val rows = anomalies.getOrPut(anomaly) { mutableListOf() }
                if (rows.size < 50) rows += index + 1
            }
        }
        return Report(text, events, anomalies, capped)
    }
    fun compare(a: Report?, b: Report?): String {
        if (a == null || b == null) return "请分别导入来源 A（平板）和来源 B（车机）。"
        val first = Stage.entries.firstOrNull { s -> a.events.any { it.stage == s } != b.events.any { it.stage == s } }
        return buildString {
            appendLine(if (first == null) "未发现阶段覆盖差异；这不表示两端连接过程完全相同。" else
                "按阶段顺序首次证据覆盖分歧：${first.label}；A=${if (a.events.any { it.stage == first }) "有证据" else "未知"}，B=${if (b.events.any { it.stage == first }) "有证据" else "未知"}。不同尝试不作因果对齐。")
            val common = a.anomalies.keys.intersect(b.anomalies.keys)
            appendLine("共有异常线索：${common.joinToString().ifBlank { "未见匹配" }}；共有不等于致命。")
            appendLine("仅 A 异常：${(a.anomalies.keys - b.anomalies.keys).joinToString().ifBlank { "未见" }}")
            appendLine("仅 B 异常：${(b.anomalies.keys - a.anomalies.keys).joinToString().ifBlank { "未见" }}")
            appendLine("仅 A 阶段：${Stage.entries.filter { s -> a.events.any { it.stage == s } && b.events.none { it.stage == s } }.joinToString { it.label }.ifBlank { "未见" }}")
            appendLine("仅 B 阶段：${Stage.entries.filter { s -> b.events.any { it.stage == s } && a.events.none { it.stage == s } }.joinToString { it.label }.ifBlank { "未见" }}")
            appendLine("建议（未执行）：同一手机、相近距离，分别观察手机弹窗并记录时间；另一设备扫描对应广播，再导入新的报告核对。CarLink 私有广告内容未知；不自动修改 RSSI、信道或原车服务。")
            append(LIMITS)
        }
    }
}
