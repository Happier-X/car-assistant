package com.carassistant.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 *  3. 执行中禁止关闭弹窗，防止半途取消导致状态不明
 */
@Composable
fun UninstallConfirmDialog(
    entry: AppLauncher.AppEntry,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
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
                    text = if (AppUninstaller.isProtectedPackage(entry.packageName)) {
                        "这是系统关键组件（桌面、系统设置、电话框架等），卸载会导致车机无法开机或无法操作，已禁止卸载。"
                    } else if (entry.isSystem) {
                        "这是系统应用。将以「仅当前用户」方式移除，" +
                            "应用数据会被清除，但安装包保留，可通过 root 恢复。"
                    } else {
                        "应用及其全部数据将被删除，此操作不可恢复。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
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
                onClick = onConfirm,
                // 保护名单内的应用后端也会拒绝，这里直接禁掉按钮
                enabled = !isBusy && !AppUninstaller.isProtectedPackage(entry.packageName),
            ) {
                Text(
                    text = if (AppUninstaller.isProtectedPackage(entry.packageName)) "无法卸载" else "卸载",
                    color = if (AppUninstaller.isProtectedPackage(entry.packageName)) {
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
