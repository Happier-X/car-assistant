package com.carassistant.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carassistant.core.BleScanner
import com.carassistant.core.ConnectionEvidence
import com.carassistant.ui.MainViewModel

@Composable
fun ConnectionDiagnosticsSection(viewModel: MainViewModel) {
    val scan by viewModel.bleScanState.collectAsStateWithLifecycle()
    val reports by viewModel.importedReports.collectAsStateWithLifecycle()
    val message by viewModel.importMessage.collectAsStateWithLifecycle()
    val importing by viewModel.importBusy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exportMessage by viewModel.connectionExportMessage.collectAsStateWithLifecycle()
    val exporting by viewModel.connectionExportBusy.collectAsStateWithLifecycle()
    var feedback by remember { mutableStateOf("") }
    var slot by rememberSaveable { mutableIntStateOf(0) }
    var names by remember { mutableStateOf(listOf("平板", "车机")) }
    var showRaw by remember { mutableStateOf(false) }
    var exportRaw by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importConnectionReport(slot, uri)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        viewModel.saveConnectionExport(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        feedback = if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) "已授权，请检查定位和蓝牙开关，再手动开始扫描。" else "精确位置权限被拒；请在应用权限设置检查后重试。"
    }
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.setScanForeground(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.setScanForeground(false)
        }
        owner.lifecycle.addObserver(observer)
        viewModel.setScanForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.setScanForeground(false) }
    }
    Text("BLE 限时扫描与保存", style = MaterialTheme.typography.headlineSmall)
    Text(BleScanner.LIMITS)
    Text(scan.message, style = MaterialTheme.typography.titleMedium)
    Text("最近扫描错误：${scan.error}")
    OutlinedButton(onClick = {
        try { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        catch (e: Exception) { feedback = "权限入口不可用：${e.javaClass.simpleName}；请到系统应用设置授权。" }
    }, modifier = Modifier.fillMaxWidth()) { Text("授权扫描所需精确位置权限") }
    OutlinedButton(onClick = {
        try { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        catch (e: Exception) { feedback = "定位设置入口不可用：${e.javaClass.simpleName}；请从原生设置检查。" }
    }, modifier = Modifier.fillMaxWidth()) { Text("检查系统定位开关") }
    Button(onClick = { viewModel.startBleScan() }, enabled = !scan.busy, modifier = Modifier.fillMaxWidth()) { Text("手动扫描 15 秒（重新采集）") }
    OutlinedButton(onClick = { viewModel.stopBleScan() }, enabled = scan.busy, modifier = Modifier.fillMaxWidth()) { Text("停止 / 重试停止自身扫描") }
    OutlinedButton(onClick = { viewModel.clearBleScan() }, enabled = !scan.busy, modifier = Modifier.fillMaxWidth()) { Text("清除扫描结果") }
    Row { Checkbox(showRaw, { showRaw = it }); Text("在本地展开扫描载荷（可能含标识）") }
    Text("结果 ${scan.rows.size}/100；另丢弃密集回调 ${scan.dropped} 条。重复广播会保留为独立观察，不等于设备数量。")
    // Show a bounded preview; all retained rows can be saved explicitly.
    scan.rows.takeLast(12).forEach { Text(if (showRaw) it.detail() else it.summary()) }
    if (scan.rows.size > 12) Text("仅预览最后 12 条；保存文件包含已保留的全部结果。")
    Row { Checkbox(exportRaw, { exportRaw = it }); Text("保存时包含扫描载荷：可能含设备/账号等标识，不进入网页报告") }
    OutlinedButton(onClick = {
        viewModel.prepareConnectionExport(exportRaw)
        try { saver.launch("连接诊断.txt") }
        catch (e: Exception) { viewModel.saveConnectionExport(null); feedback = "保存入口不可用：${e.javaClass.simpleName}；可用下方普通报告导出摘要。" }
    }, enabled = !exporting, modifier = Modifier.fillMaxWidth()) { Text(if (exporting) "正在保存…" else "保存扫描与对比 TXT（默认脱敏）") }
    if (exportMessage.isNotEmpty()) Text(exportMessage)
    Text("平板 / 车机报告导入对比", style = MaterialTheme.typography.headlineSmall)
    Text("选择用户自己的 TXT/日志；单份最多 512 KiB、12000 行。支持 UTF-8 或带 BOM 的 UTF-16，不执行文本/HTML。原文仅在本地进程内保留；普通报告与网页只导出阶段、行号及固定标签。关闭进程即清除，原文件不变。")
    Text(ConnectionEvidence.LIMITS)
    reports.forEachIndexed { index, report ->
        OutlinedTextField(value = names[index], onValueChange = { value -> names = names.toMutableList().also { it[index] = value.take(40) } },
            label = { Text("来源 ${if (index == 0) "A" else "B"} 名称（仅本地）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(onClick = {
            slot = index
            try { picker.launch(arrayOf("text/*", "application/octet-stream", "application/json")) }
            catch (e: Exception) { feedback = "文件选择入口不可用：${e.javaClass.simpleName}；请检查系统文件管理器。" }
        }, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text("导入 / 替换来源 ${if (index == 0) "A" else "B"}") }
        report?.let {
            Text("${names[index]}：${it.events.size} 条证据${if (it.capped) "（已达解析上限）" else ""}")
            it.events.take(30).forEach { event ->
                Text("${names[index]} L${event.line} · ${event.time} · ${event.stage.label}\n${event.confidence}\n${event.evidence.take(300)}")
            }
            var page by remember(index, report) { mutableIntStateOf(0) }
            var original by remember(index, report) { mutableStateOf(false) }
            TextButton(onClick = { original = !original }) { Text(if (original) "收起本地原文" else "查看本地原文证据（可能含私密信息）") }
            if (original) {
                val lines = it.text.lines()
                Text("原文 L${page * 30 + 1} 起；仅本地显示，不进入默认导出")
                Text(lines.drop(page * 30).take(30).mapIndexed { n, line -> "L${page * 30 + n + 1} $line" }.joinToString("\n"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                    TextButton(onClick = { page++ }, enabled = (page + 1) * 30 < lines.size) { Text("下一页") }
                }
            }
            if (it.events.size > 30) Text("阶段预览显示前 30 条；保存摘要包含全部已解析事件，原文可逐页查看。")
        }
    }
    Text(message)
    Text(ConnectionEvidence.compare(reports[0], reports[1]))
    OutlinedButton(onClick = { viewModel.clearConnectionReports() }, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text("清除两份导入报告") }
    if (feedback.isNotEmpty()) Text(feedback)
}
