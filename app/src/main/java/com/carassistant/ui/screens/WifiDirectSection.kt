package com.carassistant.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carassistant.core.WifiDirectController
import com.carassistant.ui.MainViewModel

@Composable
fun WifiDirectSection(viewModel: MainViewModel) {
    val state by viewModel.wifiDirectState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var feedback by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, Long?>?>(null) }
    var riskAccepted by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        feedback = if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) "精确位置权限已授予；请确认系统定位开启，再手动操作。" else "未获得精确位置权限，P2P 操作可能不可用；可在系统应用权限设置查看。"
        viewModel.refreshWifiDirect()
        viewModel.refreshDiagnostics()
    }
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.enterWifiDirect()
                Lifecycle.Event.ON_STOP -> { pending = null; riskAccepted = false; viewModel.leaveWifiDirect() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.enterWifiDirect()
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.leaveWifiDirect() }
    }
    Text("Wi-Fi Direct 直连", style = MaterialTheme.typography.headlineSmall)
    Text(WifiDirectController.LIMITS, style = MaterialTheme.typography.bodyLarge)
    Text(state.message, style = MaterialTheme.typography.titleLarge)
    Text("P2P：${state.enabled}\n发现：${state.discovery}\n系统组：${state.group}\n本机角色：${state.role}\n最近操作：${state.time}\n结果：${state.result}\n错误：${state.error}", style = MaterialTheme.typography.bodyLarge)
    OutlinedButton(onClick = { viewModel.refreshWifiDirect(); viewModel.refreshDiagnostics() }, modifier = Modifier.fillMaxWidth()) { Text("刷新直连状态（不开始发现或连接）") }
    OutlinedButton(onClick = {
        try { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        catch (e: RuntimeException) { feedback = "系统权限入口不可用（${e.javaClass.simpleName}），请到系统应用设置手动查看权限。" }
    }, modifier = Modifier.fillMaxWidth()) { Text("授权 P2P 所需精确位置权限") }
    OutlinedButton(onClick = { feedback = openP2pSettings(context, false) }, modifier = Modifier.fillMaxWidth()) { Text("打开系统 WiFi / 直连设置") }
    OutlinedButton(onClick = { feedback = openP2pSettings(context, true) }, modifier = Modifier.fillMaxWidth()) { Text("打开系统定位设置") }
    Button(onClick = { riskAccepted = false; pending = "discover" to null }, enabled = state.observing && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("手动发现设备（可能影响热点）") }
    OutlinedButton(onClick = { riskAccepted = false; pending = "create" to null }, enabled = state.observing && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("手动创建直连组（可能影响热点）") }
    Text("附近设备仅在本页显示名称用于选择，不显示 MAC，也不写入报告。连接前需对端进入 Wi-Fi Direct 设置并确认；列表为空可能是没有发现、权限/定位或厂商限制。", style = MaterialTheme.typography.bodyLarge)
    state.peers.forEach { peer ->
        OutlinedButton(onClick = { riskAccepted = false; pending = "connect" to peer.id }, enabled = !state.busy && peer.status == "可邀请", modifier = Modifier.fillMaxWidth()) {
            Text("${peer.label} · ${peer.status}")
        }
    }
    if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodyLarge)
    pending?.let { action ->
        val label = when (action.first) { "discover" -> "发现设备"; "create" -> "创建直连组"; else -> "连接所选设备" }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认$label") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("将使用共享 P2P 无线资源，可能影响手机热点、WiFi、CarLink 及正在进行的上传。不会自动切断热点，但不能保证系统并发支持。已有组/协商或状态未知时会拒绝请求。系统接受不等于已连接；本页不提供可能干扰其他应用的全局取消/拆组。离页后系统请求也可能继续，请在系统设置管理。")
                    Row { Checkbox(checked = riskAccepted, onCheckedChange = { riskAccepted = it }); Text("我已停止重要传输并了解可能影响现有连接") }
                }
            },
            confirmButton = { TextButton(onClick = { pending = null; viewModel.actWifiDirect(action.first, action.second) }, enabled = riskAccepted && !state.busy) { Text("确认$label") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }
}

private fun openP2pSettings(context: Context, location: Boolean): String {
    val actions = if (location) listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS, Settings.ACTION_SETTINGS)
        else listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
    val notes = mutableListOf<String>()
    for (action in actions) {
        try {
            val intent = Intent(action)
            if (intent.resolveActivity(context.packageManager) == null) { notes += "$action 未提供"; continue }
            context.startActivity(intent)
            return (notes + if (location) "已尝试打开定位设置；返回后刷新。" else "已尝试打开系统 WiFi/网络设置。公开 API 无标准直连专用入口，若系统有 Wi-Fi Direct 子菜单请手动进入；返回后刷新。").joinToString("；")
        } catch (e: ActivityNotFoundException) { notes += "入口被裁剪（${e.javaClass.simpleName}）" }
        catch (e: SecurityException) { notes += "系统拒绝跳转（${e.javaClass.simpleName}）" }
        catch (e: RuntimeException) { notes += "系统跳转异常（${e.javaClass.simpleName}）" }
    }
    return notes.joinToString("；") + "。请从车机原生设置手动查看。"
}
