package com.carassistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carassistant.core.ApkDownloader
import com.carassistant.core.ApkInstaller
import com.carassistant.core.InstallEnvSnapshot
import com.carassistant.core.InstallMessage
import com.carassistant.core.LocalApkScanner
import com.carassistant.core.MessageLevel
import com.carassistant.core.Permissions
import com.carassistant.core.PushServer
import com.carassistant.ui.MainViewModel
import com.carassistant.ui.common.DownloadConfirmDialog
import com.carassistant.ui.common.EmptyHint
import com.carassistant.ui.common.InstallConfirmDialog
import com.carassistant.ui.common.QrCode
import com.carassistant.ui.common.SectionCard
import com.carassistant.ui.common.StatusChip
import com.carassistant.ui.common.StatusKind
import com.carassistant.ui.icons.Download
import com.carassistant.ui.icons.Folder
import com.carassistant.ui.icons.Wifi
import com.carassistant.ui.icons.Refresh
import com.carassistant.ui.theme.CarStatusColors

/**
 * 安装 / 下载页。
 *
 * ## 为什么做成「管理」页的二级切换，而不是第 6 个标签页
 *
 * 底部导航已经有 5 个标签（状态 / 启动项 / 管理 / 设置 / 日志），
 * 车机屏幕上再加一个会把标签文字挤到几乎读不出来。而「装应用 / 卸应用」
 * 本来就是同一件事的两面 —— 都叫「应用管理」，合并成一个页面反而更符合直觉。
 *
 * ## 页面三块
 *
 * 1. **环境自检**：未知来源授权、存储权限、无障碍、root —— 缺什么、点哪里去开，一眼看到
 * 2. **下载**：粘贴 APK 直链 → 原样确认地址 → 进度/速率/取消/续传 → 下载完一键安装
 * 3. **本机 APK**：扫描内部存储与 U 盘上的 .apk，点一下走同一条安装链
 *
 * 所有结果都如实呈现：无 root 车机上安装时系统一定会弹确认框，
 * 页面不会把「已调起安装器」说成「已安装」。
 */
@Composable
fun InstallSection(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    // 只为拼「去授权」的 Intent 才需要 Context，业务状态全部走 ViewModel
    val context = LocalContext.current

    val env by viewModel.installEnv.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val downloadedMeta by viewModel.downloadedMeta.collectAsStateWithLifecycle()
    val localApks by viewModel.localApks.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningApks.collectAsStateWithLifecycle()
    val installBusy by viewModel.installBusy.collectAsStateWithLifecycle()
    val message by viewModel.installMessage.collectAsStateWithLifecycle()
    val push by viewModel.pushState.collectAsStateWithLifecycle()

    var urlInput by rememberSaveable { mutableStateOf("") }
    /** 非空时弹出「确认下载地址」弹窗 */
    var pendingDownloadUrl by remember { mutableStateOf<String?>(null) }
    /** 非空时弹出「确认安装」弹窗 */
    var pendingInstall by remember { mutableStateOf<InstallTarget?>(null) }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    // 存储权限只能在 Activity 里申请；Android 13+ 已无此权限，按钮不会显示
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onStoragePermissionResult(granted) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InstallEnvCard(
                env = env,
                onGrantUnknownSources = {
                    viewModel.openIntent(Permissions.unknownSourcesSettingsIntent(context))
                },
                onRequestStorage = {
                    storagePermissionLauncher.launch(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    )
                },
                onOpenAccessibility = {
                    viewModel.openIntent(Permissions.accessibilitySettingsIntent())
                },
            )
        }

        message?.let { current ->
            item {
                MessageBanner(current = current, onDismiss = viewModel::dismissInstallMessage)
            }
        }

        item {
            PushCard(
                push = push,
                onToggle = viewModel::togglePushServer,
                onRefresh = viewModel::refreshPushNetwork,
                onCopyAddress = { address ->
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("车机助手推送地址", address)
                    )
                    Toast.makeText(context, "推送地址已复制", Toast.LENGTH_SHORT).show()
                },
            )
        }

        item {
            DownloadCard(
                urlInput = urlInput,
                onUrlChange = { urlInput = it },
                download = download,
                downloadedMeta = downloadedMeta,
                onRequestDownload = { pendingDownloadUrl = urlInput.trim() },
                onCancel = viewModel::cancelDownload,
                onResume = { viewModel.startDownload(download.url) },
                onCleanPartial = viewModel::cleanPartialDownloads,
                onInstallDownloaded = {
                    val path = download.savedPath ?: return@DownloadCard
                    pendingInstall = InstallTarget(
                        displayName = downloadedMeta?.label.orEmpty()
                            .ifBlank { download.fileName },
                        packageName = downloadedMeta?.packageName.orEmpty(),
                        versionText = downloadedMeta?.let { "v" + it.versionName } ?: "",
                        sizeText = formatSize(download.bytesRead),
                        sourceLabel = "下载地址",
                        source = download.url,
                        path = path,
                        parseNote = if (downloadedMeta == null) {
                            "无法解析这个文件（可能不是有效 APK，或下载不完整）"
                        } else {
                            null
                        },
                    )
                },
            )
        }

        item {
            LocalApkCard(
                count = localApks.size,
                isScanning = isScanning,
                hasList = localApks.isNotEmpty(),
                onScan = viewModel::scanLocalApks,
            )
        }

        items(localApks, key = { it.path }) { entry ->
            LocalApkRow(entry = entry) {
                pendingInstall = InstallTarget(
                    displayName = entry.displayName,
                    packageName = entry.packageName,
                    versionText = if (entry.isParsed) "v" + entry.versionName else "",
                    sizeText = formatSize(entry.sizeBytes),
                    sourceLabel = if (entry.fromUsb) "U 盘文件路径" else "文件路径",
                    source = entry.path,
                    path = entry.path,
                    parseNote = entry.parseNote,
                )
            }
        }
    }

    // ---- 下载前确认地址（原样展示） ----
    pendingDownloadUrl?.let { url ->
        DownloadConfirmDialog(
            url = url,
            fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.apk" },
            onDismiss = { pendingDownloadUrl = null },
            onConfirm = {
                pendingDownloadUrl = null
                viewModel.startDownload(url)
            },
        )
    }

    // ---- 安装前知情确认 ----
    pendingInstall?.let { target ->
        InstallConfirmDialog(
            displayName = target.displayName,
            packageName = target.packageName,
            versionText = target.versionText,
            sizeText = target.sizeText,
            sourceLabel = target.sourceLabel,
            source = target.source,
            parseNote = target.parseNote,
            isBusy = installBusy,
            busyText = "正在安装，若弹出系统安装器请在车机上点「安装」…",
            canInstallUnknownSources = env?.canInstallUnknownSources ?: false,
            onGrantUnknownSources = {
                viewModel.openIntent(Permissions.unknownSourcesSettingsIntent(context))
            },
            onDismiss = { if (!installBusy) pendingInstall = null },
            onConfirm = {
                viewModel.installApk(target.path, target.sourceLabel + "：" + target.source) {
                    pendingInstall = null
                }
            },
        )
    }
}

/** 待安装目标（下载产物或本机文件都归一到这个结构） */
private data class InstallTarget(
    val displayName: String,
    val packageName: String,
    val versionText: String,
    val sizeText: String,
    val sourceLabel: String,
    val source: String,
    val path: String,
    val parseNote: String?,
)

// ==================== 环境自检 ====================

@Composable
private fun InstallEnvCard(
    env: InstallEnvSnapshot?,
    onGrantUnknownSources: () -> Unit,
    onRequestStorage: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    SectionCard(
        title = "安装环境自检",
        subtitle = "缺哪一项点哪一项，缺少也能装，只是过程不同",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EnvRow(
                title = "安装未知来源应用",
                ok = env?.canInstallUnknownSources == true,
                detail = if (env?.canInstallUnknownSources == true) {
                    "已允许，可以直接走系统安装器"
                } else {
                    "未允许（Android 8+ 是按应用单独开关的），需要先开启"
                },
                actionText = "去开启",
                onAction = onGrantUnknownSources,
            )
            EnvRow(
                title = "存储读取权限",
                ok = env?.hasReadStorage == true,
                detail = if (env?.hasReadStorage == true) {
                    "可以扫描内部存储与 U 盘上的 APK"
                } else if (Permissions.shouldRequestReadStorage()) {
                    "未授予，本机 APK 可能扫不到"
                } else {
                    "Android 13+ 无独立存储权限，按旧版存储模型尝试扫描"
                },
                actionText = if (Permissions.shouldRequestReadStorage()) "授予" else null,
                onAction = onRequestStorage,
            )
            EnvRow(
                title = "无障碍服务",
                ok = env?.accessibilityReady == true,
                detail = if (env?.accessibilityReady == true) {
                    "可代点系统安装确认（安装链 I4 兜底）"
                } else {
                    "未开启，无 root 时需要你自己点系统安装器的「安装」"
                },
                actionText = "去开启",
                onAction = onOpenAccessibility,
            )
            EnvRow(
                title = "root 静默安装",
                ok = env?.rootAvailable == true,
                detail = if (env?.rootAvailable == true) {
                    "可用，安装链会先试 I1/I2 完全静默安装"
                } else {
                    "不可用（未 root 或设置里关掉了「允许使用 root」），走系统安装器"
                },
                actionText = null,
                onAction = null,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "提示：本应用不做静默安装的假象 —— 没有 root 时每个包都要你在" +
                    "系统安装器里点一次「安装」，日志与结果区会如实记录最终有没有装上。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EnvRow(
    title: String,
    ok: Boolean,
    detail: String,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(
                    text = if (ok) "就绪" else "未就绪",
                    kind = if (ok) StatusKind.Success else StatusKind.Warning,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionText != null && onAction != null && !ok) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

// ==================== 结果提示横幅 ====================

@Composable
private fun MessageBanner(current: InstallMessage, onDismiss: () -> Unit) {
    val color = when (current.level) {
        MessageLevel.OK -> CarStatusColors.success
        MessageLevel.WARN -> CarStatusColors.warning
        MessageLevel.ERROR -> CarStatusColors.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.text,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

// ==================== 手机推送（局域网） ====================

@Composable
private fun PushCard(
    push: PushServer.Snapshot,
    onToggle: () -> Unit,
    onCopyAddress: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val lan = push.lan
    val wifiText = when {
        lan.ssid.isNotBlank() -> lan.ssid
        lan.connectedViaWifi -> "已连接（SSID 被系统隐藏）"
        else -> "未连接"
    }

    SectionCard(
        title = "手机推送安装（推荐）",
        subtitle = "车机连手机热点，手机浏览器打开地址即可上传 —— 手机端不用装任何 App",
        icon = Icons.Filled.Wifi,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = when (push.state) {
                        PushServer.State.RUNNING -> "推送服务运行中"
                        PushServer.State.WAITING_NETWORK -> "等待连接热点"
                        PushServer.State.FAILED -> "推送不可用"
                        PushServer.State.STOPPED -> "推送服务已停止"
                    },
                    kind = when (push.state) {
                        PushServer.State.RUNNING -> StatusKind.Success
                        PushServer.State.WAITING_NETWORK -> StatusKind.Warning
                        PushServer.State.FAILED -> StatusKind.Error
                        PushServer.State.STOPPED -> StatusKind.Neutral
                    },
                )
                if (push.running && push.port > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "端口 " + push.port,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 网络信息：每次都从 ConnectivityManager / WifiManager 实时读 ----
            Text(
                text = buildString {
                    append("当前 WiFi：").append(wifiText)
                    if (lan.ipAddress != null) append("    车机 IP：").append(lan.ipAddress)
                    if (lan.gateway != null) append("    网关（手机）：").append(lan.gateway)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (push.primaryUrl.isNotBlank()) {
                Text(
                    text = push.primaryUrl,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onCopyAddress(push.primaryUrl) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Text(
                    text = "点一下地址即可复制。手机连同一个热点后用浏览器打开，" +
                        "不需要装任何 App。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 二维码放在地址正下方。地址和二维码都保留：
                // 手机上扫码最省事，扫不出来时还能点地址复制或直接手输。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QrCode(content = push.primaryUrl)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "手机扫一扫，直接打开推送页（扫不出来时可以手输上面的地址）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    text = push.message.ifBlank {
                        if (lan.ipAddress == null) {
                            "WiFi 已开但未连接手机热点，推送暂不可用；连上后会自动刷新出地址"
                        } else {
                            "推送服务未开启"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (push.state) {
                        PushServer.State.FAILED -> CarStatusColors.error
                        PushServer.State.WAITING_NETWORK -> CarStatusColors.warning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (push.state == PushServer.State.STOPPED && lan.ipAddress != null) {
                    Text(
                        text = "开启后手机访问：http://" + lan.ipAddress + ":8080" +
                            "（端口被占用时会自动顺延）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (push.waitingForHotspot) {
                Text(
                    text = "连上手机热点并拿到 IP 后会自动就绪，不需要重新点开启；" +
                        "也可以先用下面的「本机 / U 盘 APK」安装。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (push.receiving) {
                val percent = push.percent
                if (percent >= 0) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = buildString {
                        append("正在接收：").append(push.lastFileName).append("  ")
                        if (percent >= 0) append(percent).append("%  ")
                        append(formatSize(push.receivedBytes))
                        if (push.expectedBytes > 0) {
                            append(" / ").append(formatSize(push.expectedBytes))
                        }
                        if (push.lastClientIp.isNotBlank()) {
                            append("  来自 ").append(push.lastClientIp)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else if (push.lastFileName.isNotBlank()) {
                Text(
                    text = "最近一次：" + push.lastFileName + "（" +
                        formatSize(push.lastSizeBytes) + "，耗时 " +
                        (push.lastCostMs / 1000.0).toInt() + " 秒）来自 " + push.lastClientIp,
                    style = MaterialTheme.typography.bodySmall,
                    color = CarStatusColors.success,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle) {
                    Text(
                        if (push.running || push.state == PushServer.State.WAITING_NETWORK) {
                            "停止推送服务"
                        } else {
                            "开启手机推送"
                        }
                    )
                }
                OutlinedButton(onClick = onRefresh) { Text("刷新地址") }
            }

            Text(
                text = "安全说明：页面仅在车机所在局域网内可见；上传目录固定、不接受任何目标路径参数，" +
                    "也没有远程执行能力。上传完成后车机会自动走安装链，" +
                    "无 root 时仍需你在车机上点一次「安装」。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
// ==================== 下载（备用入口） ====================

@Composable
private fun DownloadCard(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    download: ApkDownloader.Snapshot,
    downloadedMeta: ApkInstaller.ApkMeta?,
    onRequestDownload: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onCleanPartial: () -> Unit,
    onInstallDownloaded: () -> Unit,
) {
    SectionCard(
        title = "下载 APK（直链）",
        subtitle = "备用入口：手机推送更省事；这里只支持 http/https 直链",
        icon = Icons.Filled.Download,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://example.com/app.apk") },
                label = { Text("APK 直链地址") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                enabled = !download.isRunning,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequestDownload,
                    enabled = urlInput.isNotBlank() && !download.isRunning,
                ) {
                    Text("下载")
                }
                if (download.isRunning) {
                    OutlinedButton(onClick = onCancel) { Text("取消下载") }
                } else if (download.canResume) {
                    OutlinedButton(onClick = onResume) { Text("继续下载") }
                }
                if (download.state != ApkDownloader.State.IDLE) {
                    OutlinedButton(onClick = onCleanPartial) { Text("清理未完成分片") }
                }
            }

            if (download.state != ApkDownloader.State.IDLE) {
                DownloadProgress(download)
            }

            if (download.state == ApkDownloader.State.DONE) {
                Text(
                    text = buildString {
                        append("文件：")
                        append(download.fileName)
                        append("（")
                        append(formatSize(download.bytesRead))
                        append("）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloadedMeta != null) {
                    Text(
                        text = "解析结果：" + downloadedMeta.label + " " +
                            downloadedMeta.versionName + "（" + downloadedMeta.packageName + "）",
                        style = MaterialTheme.typography.bodySmall,
                        color = CarStatusColors.success,
                    )
                } else {
                    Text(
                        text = "解析结果：无法识别这个文件，安装前请再确认来源",
                        style = MaterialTheme.typography.bodySmall,
                        color = CarStatusColors.error,
                    )
                }
                download.savedPath?.let { path ->
                    Text(
                        text = "保存位置：" + path,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(onClick = onInstallDownloaded) { Text("安装这个 APK") }
            }
        }
    }
}

@Composable
private fun DownloadProgress(download: ApkDownloader.Snapshot) {
    val percent = download.percent
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (percent >= 0) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (download.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = buildString {
                append(
                    if (percent >= 0) {
                        "进度 " + percent + "%  "
                    } else {
                        "进度未知（服务端未返回长度）  "
                    },
                )
                append(formatSize(download.bytesRead))
                if (download.totalBytes > 0) append(" / " + formatSize(download.totalBytes))
                if (download.isRunning && download.bytesPerSecond > 0) {
                    append("  " + formatSize(download.bytesPerSecond) + "/s")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (download.resumedFrom > 0) {
            Text(
                text = "本次从 " + formatSize(download.resumedFrom) + " 处续传",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (download.message.isNotBlank()) {
            Text(
                text = download.message,
                style = MaterialTheme.typography.bodySmall,
                color = when (download.state) {
                    ApkDownloader.State.DONE -> CarStatusColors.success
                    ApkDownloader.State.FAILED -> CarStatusColors.error
                    ApkDownloader.State.CANCELLED -> CarStatusColors.warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ==================== 本机 APK ====================

@Composable
private fun LocalApkCard(
    count: Int,
    isScanning: Boolean,
    hasList: Boolean,
    onScan: () -> Unit,
) {
    SectionCard(
        title = "安装本机 / U 盘 APK",
        subtitle = "扫描内部存储与 U 盘挂载点，扫不到不代表文件不存在",
        icon = Icons.Filled.Folder,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onScan, enabled = !isScanning) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isScanning) "扫描中…" else "扫描本机 APK")
                }
                if (hasList) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "找到 " + count + " 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "会扫描：本应用下载目录、内部存储（/sdcard）、常见 U 盘挂载点" +
                    "（/mnt/usb*、/mnt/media_rw、/storage/*）。每个目录的可读结果都会写进日志页。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isScanning && !hasList) {
                EmptyHint("还没有扫描结果\n点上面按钮扫描车机上的 .apk 文件")
            }
        }
    }
}

@Composable
private fun LocalApkRow(entry: LocalApkScanner.Entry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isParsed) {
                        entry.packageName + " · v" + entry.versionName
                    } else {
                        entry.fileName
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSize(entry.sizeBytes) + " · " + entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                !entry.isParsed -> StatusChip("无法解析", StatusKind.Error)
                entry.fromUsb -> StatusChip("U 盘", StatusKind.Info)
                else -> StatusChip("可安装", StatusKind.Neutral)
            }
        }
    }
}

// ==================== 小工具 ====================

/** 字节数转人类可读；下载速率也用同一套单位 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
