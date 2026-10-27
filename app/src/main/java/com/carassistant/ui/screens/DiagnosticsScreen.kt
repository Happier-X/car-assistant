package com.carassistant.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carassistant.core.CarDiagnostics
import com.carassistant.core.PushServer
import com.carassistant.core.BleAdvertiserTest
import com.carassistant.ui.MainViewModel
import com.carassistant.ui.common.QrCode

@Composable
fun DiagnosticsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val snapshot by viewModel.diagnostics.collectAsStateWithLifecycle()
    val busy by viewModel.diagnosticBusy.collectAsStateWithLifecycle()
    val report by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val published by viewModel.diagnosticReportPublished.collectAsStateWithLifecycle()
    val push by viewModel.pushState.collectAsStateWithLifecycle()
    val ble by viewModel.bleTestState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var feedback by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopBleTest("已离开车机诊断页") }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("车机诊断", style = MaterialTheme.typography.headlineMedium)
        Text("信息只读；BLE 广播须手动测试 · 无需 root。请停车后操作。", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { viewModel.refreshDiagnostics() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "正在后台读取…" else "刷新诊断（清除旧报告）")
        }
        snapshot?.let { data ->
            Text("采集时间：${data.time}", style = MaterialTheme.typography.bodyLarge)
            data.fields.forEach { field ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(field.label, style = MaterialTheme.typography.titleMedium)
                        Text(field.value, style = MaterialTheme.typography.titleLarge)
                        Text("来源：${field.source}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Text(CarDiagnostics.LIMITS, style = MaterialTheme.typography.bodyLarge)
        WifiDirectSection(viewModel)
        ConnectionDiagnosticsSection(viewModel)
        Text("BLE 蓝牙广播测试", style = MaterialTheme.typography.headlineSmall)
        Text("能力信息见上方蓝牙字段；返回系统设置后点击刷新。测试不会自动开启蓝牙，不启动连接或扫描，不影响其他应用的广播。低功耗、低发射功率、不可连接，最长 30 秒；离开本页或应用进入后台会请求停止。", style = MaterialTheme.typography.bodyLarge)
        Text(ble.message, style = MaterialTheme.typography.titleLarge)
        Text("最近测试：${ble.time}\n结果：${ble.result}\n错误：${ble.error}\n停止记录：${ble.cleanup}\n迟到回调清理：${ble.lateCleanup}\n开关监听：${ble.receiver}", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { viewModel.startBleTest() }, enabled = !ble.busy, modifier = Modifier.fillMaxWidth()) {
            Text("开始 BLE 测试广播（30 秒）")
        }
        OutlinedButton(onClick = { viewModel.stopBleTest() }, enabled = ble.busy, modifier = Modifier.fillMaxWidth()) { Text("停止 / 重试停止 BLE 测试") }
        OutlinedButton(onClick = { feedback = openBluetoothSettings(context, true) }, modifier = Modifier.fillMaxWidth()) { Text("请求系统开启蓝牙") }
        OutlinedButton(onClick = { feedback = openBluetoothSettings(context, false) }, modifier = Modifier.fillMaxWidth()) { Text("打开蓝牙设置 / 权限") }
        Text(BleAdvertiserTest.LIMITS, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { feedback = openDiagnosticSettings(context, true) }, modifier = Modifier.fillMaxWidth()) {
            Text("尝试打开开发者选项")
        }
        OutlinedButton(onClick = { feedback = openDiagnosticSettings(context, false) }, modifier = Modifier.fillMaxWidth()) {
            Text("打开系统关于页 / 设置")
        }
        Text("手机取走报告", style = MaterialTheme.typography.headlineSmall)
        Text("1. 车机连接手机热点或可互访的局域网。\n2. 主动开启下方局域网服务。此开关同时控制 APK 上传安装与报告访问；同网设备可访问，请仅在可信网络开启。\n3. 点击生成报告，再用手机扫码或手输地址；网页可查看或下载 TXT。\n4. 用完关闭服务。报告仅在内存保留 10 分钟，刷新诊断、停服或应用结束会清除；已下载/复制的副本需自行删除。", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { viewModel.togglePushServer() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (push.state == PushServer.State.RUNNING || push.state == PushServer.State.WAITING_NETWORK) "关闭局域网服务（上传 + 报告）" else "开启局域网服务（上传 + 报告）")
        }
        Text(push.message.ifBlank { "局域网服务未开启" }, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { viewModel.refreshPushNetwork() }, modifier = Modifier.fillMaxWidth()) { Text("刷新局域网地址") }
        Button(onClick = { feedback = ""; viewModel.refreshDiagnostics(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("生成最新诊断报告（有效 10 分钟）")
        }
        Text(if (report == null) "暂无有效报告，请点击生成。" else if (push.running && published) "报告已生成。扫码打开网页后查看或下载。" else "报告已生成，可复制/分享；尚未供网页访问。请等局域网服务就绪后重新生成。", style = MaterialTheme.typography.bodyLarge)
        if (push.running && push.primaryUrl.isNotBlank()) {
            Text(push.primaryUrl, style = MaterialTheme.typography.titleLarge)
            QrCode(content = push.primaryUrl)
            Text("二维码打开现有局域网页面，保留 APK 上传入口。热点隔离或浏览器限制 HTTP 时可能无法访问，可手输地址或换允许互访的网络。", style = MaterialTheme.typography.bodyLarge)
        }
        OutlinedButton(onClick = {
            val text = report ?: return@OutlinedButton
            feedback = try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard == null) "系统剪贴板不可用，可尝试分享或手机网页下载。" else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("车机诊断报告", text))
                    "诊断报告已复制到剪贴板。"
                }
            } catch (_: Exception) { "复制失败，系统限制剪贴板；可尝试分享或手机网页下载。" }
        }, enabled = report != null, modifier = Modifier.fillMaxWidth()) { Text("复制诊断报告") }
        OutlinedButton(onClick = {
            val text = report ?: return@OutlinedButton
            feedback = try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "分享诊断报告"))
                "已打开系统分享面板，请选择接收应用。"
            } catch (_: ActivityNotFoundException) { "系统未提供分享应用，请复制报告或用手机网页下载。" }
            catch (_: SecurityException) { "系统禁止分享，请复制报告或用手机网页下载。" }
        }, enabled = report != null, modifier = Modifier.fillMaxWidth()) { Text("分享诊断报告") }
        if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.titleMedium)
    }
}

private fun openBluetoothSettings(context: Context, requestEnable: Boolean): String {
    val intents = buildList {
        if (requestEnable) add(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE) to "系统蓝牙开启确认页")
        add(Intent(Settings.ACTION_BLUETOOTH_SETTINGS) to "蓝牙设置")
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")) to "本应用权限设置")
        add(Intent(Settings.ACTION_SETTINGS) to "系统设置")
    }
    val notes = mutableListOf<String>()
    for ((intent, label) in intents) {
        try {
            if (intent.resolveActivity(context.packageManager) == null) { notes += "$label 不可用"; continue }
            context.startActivity(intent)
            return (notes + "已请求打开$label，是否开启以系统及你的确认为准；返回后刷新诊断。若权限受限，可在系统应用设置查看本应用权限。").joinToString("；")
        } catch (e: SecurityException) { notes += "$label 被系统拒绝（${e.javaClass.simpleName}）" }
        catch (e: ActivityNotFoundException) { notes += "$label 已被裁剪（${e.javaClass.simpleName}）" }
        catch (e: RuntimeException) { notes += "$label 跳转异常（${e.javaClass.simpleName}）" }
    }
    return notes.joinToString("；") + "。请从车机原生设置手动查看蓝牙及本应用权限。"
}

/** Only documented Settings actions; trimmed or protected vendor pages fall back safely. */
private fun openDiagnosticSettings(context: Context, developer: Boolean): String {
    val actions = if (developer) listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)
        else listOf(Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)
    val failures = mutableListOf<String>()
    for (action in actions) {
        val label = when (action) {
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS -> "开发者选项"
            Settings.ACTION_DEVICE_INFO_SETTINGS -> "系统关于页"
            else -> "系统设置"
        }
        try {
            val intent = Intent(action)
            if (intent.resolveActivity(context.packageManager) == null) {
                failures += "$label：系统未提供入口"
                continue
            }
            context.startActivity(intent)
            return (failures + "已尝试打开$label；页面内容由车机系统决定，返回后请刷新诊断。").joinToString("；")
        } catch (_: ActivityNotFoundException) { failures += "$label：入口已被裁剪或不可用" }
        catch (_: SecurityException) { failures += "$label：系统拒绝访问" }
        catch (_: RuntimeException) { failures += "$label：系统跳转异常" }
    }
    return failures.joinToString("；") + "。请从车机原生设置手动查看。"
}
