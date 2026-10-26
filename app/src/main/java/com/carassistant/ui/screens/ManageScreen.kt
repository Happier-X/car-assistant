package com.carassistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import com.carassistant.ui.icons.Delete
import com.carassistant.ui.icons.Search
import com.carassistant.core.AppLauncher
import com.carassistant.core.AppUninstaller
import com.carassistant.ui.MainViewModel
import com.carassistant.ui.common.EmptyHint
import com.carassistant.ui.common.UninstallConfirmDialog
import com.carassistant.ui.theme.CarStatusColors

/**
 * 应用管理页（卸载 + 安装下载）。
 *
 * 独立的卸载入口：列出车机上全部应用（包括没有启动入口的预装应用），
 * 点应用弹出卸载确认。相比藏在「启动项 → 添加应用」里的卸载小图标，
 * 这里是明确的功能页面，方便清理车机上不想要的预装软件。
 *
 * 设计要点：
 *  - 默认只显示第三方应用（也只能卸载第三方），防止在车机上误触误删系统应用；
 *    「显示系统应用」开关打开后才列出系统应用（预装清理走「仅当前用户」可恢复路径）
 *  - 系统应用标「系统」角标，保护名单内的应用点开也只会看到「无法卸载」
 *  - 搜索同时匹配应用名和包名（预装应用的显示名常常和包名对不上）
 *  - 卸载成功后列表自动刷新（ViewModel 已处理），被删的应用即刻消失
 *
 * 从 v1.3.0 起本页分成两个二级页：「卸载应用」与「安装下载」。
 * 不再新增第 6 个底部标签，是因为车机屏幕上 5 个标签已经排满，
 * 而「装应用 / 卸应用」本来就是同一件事的两面。
 */
@Composable
fun ManageScreen(
    viewModel: MainViewModel,
    availableApps: List<AppLauncher.AppEntry>,
    filteredApps: List<AppLauncher.AppEntry>,
    query: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    /** 待确认卸载的应用；非空时显示确认弹窗 */
    var pendingUninstall by remember { mutableStateOf<AppLauncher.AppEntry?>(null) }

    /** 卸载执行中（防止重复点击） */
    var uninstalling by remember { mutableStateOf(false) }

    /** 是否显示系统应用；默认关闭 = 只能卸载第三方应用，防误删 */
    var showSystemApps by rememberSaveable { mutableStateOf(false) }

    /** 二级页：卸载 / 安装下载（默认卸载，保持原有使用习惯） */
    var mode by rememberSaveable { mutableStateOf(ManageMode.Uninstall) }

    /** 当前列表实际展示的应用（按系统应用开关过滤） */
    val visibleApps = if (showSystemApps) filteredApps else filteredApps.filter { !it.isSystem }

    Column(modifier = modifier) {
        // ---- 二级切换：卸载应用 / 安装下载 ----
        ManageModeSwitcher(mode = mode, onSelect = { mode = it })

        if (mode == ManageMode.Install) {
            InstallSection(viewModel = viewModel, modifier = Modifier.weight(1f))
            return@Column
        }

        // ---- 搜索框 ----
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setAppQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            placeholder = { Text("搜索应用名或包名") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        // ---- 统计与过滤条 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (showSystemApps) {
                        "共 ${visibleApps.size} 个应用"
                    } else {
                        "第三方 ${visibleApps.size} 个 · 共 ${availableApps.size} 个"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "root 卸载无弹窗 · 无 root 走系统确认页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "显示系统应用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                Switch(
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it },
                )
            }
        }

        // ---- 应用列表 ----
        Box(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "正在扫描应用…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (visibleApps.isEmpty()) {
                EmptyHint(
                    when {
                        query.isNotBlank() -> "没有匹配的应用"
                        showSystemApps -> "没有扫描到任何应用"
                        else -> "没有第三方应用，打开「显示系统应用」查看全部"
                    }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visibleApps, key = { it.packageName }) { entry ->
                        ManageAppRow(
                            entry = entry,
                            onClick = { pendingUninstall = entry },
                        )
                    }
                }
            }
        }
    }

    // ---- 卸载确认弹窗 ----
    pendingUninstall?.let { entry ->
        UninstallConfirmDialog(
            entry = entry,
            isBusy = uninstalling,
            onDismiss = { if (!uninstalling) pendingUninstall = null },
            onConfirm = {
                uninstalling = true
                viewModel.uninstallApp(entry.packageName) { _, _ ->
                    uninstalling = false
                    pendingUninstall = null
                }
            },
        )
    }
}

@Composable
private fun ManageAppRow(
    entry: AppLauncher.AppEntry,
    onClick: () -> Unit,
) {
    val protected = AppUninstaller.isProtectedPackage(entry.packageName)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label.ifBlank { entry.packageName },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 角标：系统应用 / 受保护 / 无启动入口
            if (protected) {
                Badge("受保护", CarStatusColors.warning)
            } else if (entry.isSystem) {
                Badge("系统", CarStatusColors.neutral)
            } else if (!entry.hasLauncher) {
                Badge("无入口", CarStatusColors.neutral)
            }

            IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "卸载 ${entry.label}",
                    tint = if (protected) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    } else {
                        CarStatusColors.error
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** 管理页的两个二级页 */
private enum class ManageMode(val label: String) {
    Uninstall("卸载应用"),
    Install("安装下载"),
}

/**
 * 二级页切换器。
 *
 * 用自绘的双段控件而不是 Material 的 TabRow：车机上热区要大、文字要清楚，
 * 两段控件正好占满一行，比标签栏更容易点中（颠簸路面手指定位精度低）。
 */
@Composable
private fun ManageModeSwitcher(
    mode: ManageMode,
    onSelect: (ManageMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ManageMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onSelect(candidate) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = candidate.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
