package com.carassistant.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carassistant.core.AppLauncher
import com.carassistant.core.AppUninstaller
import com.carassistant.ui.theme.CarStatusColors

/**
 * 卸载前的二次确认弹窗（共享组件）。
 *
 * 「启动项」和「应用管理」两处都用它，保证行为一致：
 *  1. 明确列出「将被删除」的应用名和包名
 *  2. 系统应用额外说明「仅当前用户移除、可恢复」
 *  3. 系统应用必须手动勾选「我了解这是系统应用」才能卸载——车机上误触
 *     连点很常见，光靠「弹窗 + 按钮」拦不住无意识的连续点击
 *  4. 保护名单内的应用（桌面、系统设置、电话框架等）直接禁用，不给入口
 *  5. 执行中禁止关闭弹窗，防止半途取消导致状态不明
 */
@Composable
fun UninstallConfirmDialog(
    entry: AppLauncher.AppEntry,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 系统应用的「知情勾选」：每次打开弹窗都是未勾选状态，
    // 必须有意识地勾一次才能卸载
    var systemAck by remember(entry.packageName) { mutableStateOf(false) }
    val isProtected = AppUninstaller.isProtectedPackage(entry.packageName)
    val isSystem = entry.isSystem && !isProtected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("卸载应用？") },
        text = {
            Column {
                Text(
                    text = entry.label.ifBlank { entry.packageName },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (isProtected) {
                        "这是系统关键组件（桌面、系统设置、电话框架等），卸载会导致车机无法开机或无法操作，已禁止卸载。"
                    } else if (entry.isSystem) {
                        "这是本机自带的系统应用。将以「仅当前用户」方式移除，" +
                            "应用数据会被清除，但安装包保留，可通过 root 恢复。"
                    } else {
                        "应用及其全部数据将被删除，此操作不可恢复。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (isSystem) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidthSafe()
                            .clickable { if (!isBusy) systemAck = !systemAck },
                    ) {
                        Checkbox(
                            checked = systemAck,
                            onCheckedChange = { if (!isBusy) systemAck = it },
                            enabled = !isBusy,
                        )
                        Text(
                            text = "我了解这是系统应用，仍要卸载",
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
                            "正在卸载…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 双保险：即便 UI 出现异常状态，系统应用未勾选也绝不触发卸载
                    if (isProtected || (isSystem && !systemAck)) return@TextButton
                    onConfirm()
                },
                enabled = !isBusy && !isProtected && (!isSystem || systemAck),
            ) {
                Text(
                    text = when {
                        isProtected -> "无法卸载"
                        isSystem && !systemAck -> "先勾选确认"
                        else -> "卸载"
                    },
                    color = if (isProtected || (isSystem && !systemAck)) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        CarStatusColors.error
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("取消")
            }
        },
    )
}

/** 弹窗内容宽度受 AlertDialog 约束，这里只是语义化包装 */
private fun Modifier.fillMaxWidthSafe(): Modifier = this
