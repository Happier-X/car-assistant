package com.carassistant.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carassistant.ui.theme.CarStatusColors

/**
 * 安装前的知情确认弹窗（共享组件）。
 *
 * 车机上装一个来路不明的 APK，风险比手机上更大（车机往往没有杀毒、
 * 也没有第二次机会），所以这里比卸载确认更严格：
 *
 *  1. **来源必须原样展示**：下载地址 / 文件路径一字不改地显示出来，
 *     杀掉「后台偷偷下载安装」的可能
 *  2. **必须手动勾选**才能点「安装」——车机触摸屏误触率极高，
 *     光靠「弹窗 + 按钮」拦不住无意识的连点
 *  3. 解析不出包名的文件直接禁用安装（装也装不上，不如早点说清）
 *  4. 没开「安装未知应用」时给出直达授权页的入口
 */
@Composable
fun InstallConfirmDialog(
    displayName: String,
    packageName: String,
    versionText: String,
    sizeText: String,
    sourceLabel: String,
    source: String,
    parseNote: String?,
    isBusy: Boolean,
    busyText: String,
    canInstallUnknownSources: Boolean,
    onGrantUnknownSources: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 每次打开都是未勾选状态：必须有意识地勾一次才能装
    var acknowledged by remember(source) { mutableStateOf(false) }
    val blocked = parseNote != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安装这个 APK？") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = displayName.ifBlank { "未命名 APK" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (packageName.isNotBlank()) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (versionText.isNotBlank() || sizeText.isNotBlank()) {
                    Text(
                        text = listOf(versionText, sizeText).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = source,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = parseNote
                        ?: "系统会询问是否安装。无 root 的车机上还需要你在系统安装器里" +
                        "手动点一次「安装」——这是 Android 8+ 的硬性要求，本应用无法绕过，" +
                        "也会如实告诉你最终有没有装上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blocked) CarStatusColors.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!canInstallUnknownSources && !blocked) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "当前还没允许本应用「安装未知应用」，点下面的按钮去开启后再试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = CarStatusColors.warning,
                    )
                    TextButton(onClick = onGrantUnknownSources, enabled = !isBusy) {
                        Text("去开启「安装未知应用」")
                    }
                }

                if (!blocked) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isBusy) { acknowledged = !acknowledged },
                    ) {
                        Checkbox(
                            checked = acknowledged,
                            onCheckedChange = { if (!isBusy) acknowledged = it },
                            enabled = !isBusy,
                        )
                        Text(
                            text = "我确认来源可信，并了解安装未知来源 APK 的风险",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isBusy) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = busyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!blocked && acknowledged) onConfirm() },
                enabled = !isBusy && !blocked && acknowledged,
            ) {
                Text(
                    text = when {
                        blocked -> "无法安装"
                        !acknowledged -> "先勾选确认"
                        else -> "安装"
                    },
                    color = if (blocked || !acknowledged) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("取消") }
        },
    )
}

/**
 * 下载前的确认弹窗。
 *
 * 存在的唯一目的：**把下载地址原样摆到用户面前**。
 * 不允许「点一下就开始后台下载」，也不允许地址被截断或改写 ——
 * 车机上装错一个包，代价可能是整台车机要恢复出厂。
 */
@Composable
fun DownloadConfirmDialog(
    url: String,
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从这个地址下载？") },
        text = {
            Column {
                Text(
                    text = "下载地址（原样显示，请自行确认可信）：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "保存为：$fileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "下载完成后不会自动安装，需要你点「安装」并再确认一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
