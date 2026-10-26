package com.carassistant.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Public Android APIs only. No shell, identifiers, root probes or network scans. */
object CarDiagnostics {
    data class Field(val label: String, val value: String, val source: String)
    data class Snapshot(val time: String, val fields: List<Field>) {
        fun report(): String = buildString {
            appendLine("车机助手 · 诊断快照")
            appendLine("采集时间：$time")
            fields.forEach { appendLine("${it.label}：${it.value}\n  来源：${it.source}") }
            appendLine(LIMITS)
        }
    }

    const val LIMITS = "仅为采集时刻的只读快照，不代表当前状态。ADB 配置开关不等于 adbd 正在运行、端口可达或已授权连接；普通应用无法确认实际 ADB 连接，本页未探测端口。Android 10 没有 Android 11 的标准无线调试配对功能；厂商可能裁剪开发者页面。未读取车辆总线，不承诺车速、油量、胎压可读。内存/存储是 Android 可见范围。报告不含 VIN、序列号、Android ID、MAC、账号、密码、token 或应用清单。局域网 HTTP 无加密，同网设备可取报告，请仅在可信网络短时开启服务。"

    fun collect(context: Context): Snapshot {
        val fields = mutableListOf<Field>()
        fun field(label: String, source: String, read: () -> String?) {
            val value = try {
                read()?.takeIf { it.isNotBlank() && !it.equals("unknown", true) } ?: "未知 / 不可读取"
            } catch (_: SecurityException) {
                "不可读取（系统权限限制）"
            } catch (_: Exception) {
                "未知 / 不可读取（系统未提供）"
            }
            fields += Field(label, value.take(2048), source)
        }
        field("品牌", "Build.BRAND") { Build.BRAND }
        field("厂家", "Build.MANUFACTURER") { Build.MANUFACTURER }
        field("型号", "Build.MODEL") { Build.MODEL }
        field("设备", "Build.DEVICE") { Build.DEVICE }
        field("硬件", "Build.HARDWARE") { Build.HARDWARE }
        field("Android 版本 / API", "Build.VERSION.RELEASE / SDK_INT") { "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}" }
        field("安全补丁", "Build.VERSION.SECURITY_PATCH") { Build.VERSION.SECURITY_PATCH }
        field("构建号", "Build.DISPLAY") { Build.DISPLAY }
        field("CPU 架构", "Build.SUPPORTED_ABIS") { Build.SUPPORTED_ABIS.joinToString(", ") }
        field("内存：总量 / 当前可用", "ActivityManager.MemoryInfo") {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.let {
                val info = ActivityManager.MemoryInfo()
                it.getMemoryInfo(info)
                "${size(info.totalMem)} / ${size(info.availMem)}"
            }
        }
        field("应用数据所在分区：总量 / 可用", "StatFs(filesDir)，与系统共享分区") {
            val stat = StatFs(context.filesDir.absolutePath)
            "${size(stat.totalBytes)} / ${size(stat.availableBytes)}"
        }
        field("WiFi 局域网 IPv4", "ConnectivityManager WiFi LinkProperties") {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            cm?.allNetworks?.asSequence()?.filter {
                cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }?.flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                ?.map { it.address }?.filterIsInstance<java.net.Inet4Address>()
                ?.filter { !it.isLoopbackAddress && (it.isSiteLocalAddress || it.isLinkLocalAddress) }
                ?.mapNotNull { it.hostAddress }?.distinct()?.joinToString(", ")
        }
        fun setting(label: String, key: String) = field(label, "Settings.Global.$key（配置值）") {
            when (val value = Settings.Global.getString(context.contentResolver, key)) {
                null -> null
                "0" -> "关闭（配置值 0）"
                "1" -> "开启（配置值 1；不代表已连接）"
                else -> "未知配置值：$value"
            }
        }
        setting("USB ADB 配置", Settings.Global.ADB_ENABLED)
        setting("开发者选项配置", Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        field("实际 ADB 连接", "普通侧载应用权限边界；未扫描或连接端口") { "未知 / 无法确认" }
        fields += BleAdvertiserTest.capabilityFields(context)
        fields += WifiDirectController.capabilityFields(context)
        return Snapshot(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()), fields)
    }

    private fun size(bytes: Long) = String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
}
