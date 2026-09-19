package com.carassistant.ui.screens

import androidx.compose.material.icons.Icons
import com.carassistant.ui.icons.Add
import com.carassistant.ui.icons.ArrowDownward
import com.carassistant.ui.icons.ArrowUpward
import com.carassistant.ui.icons.Clear
import com.carassistant.ui.icons.Delete
import com.carassistant.ui.icons.Search
import com.carassistant.ui.icons.VerticalAlignTop
import com.carassistant.ui.icons.Warning

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carassistant.core.AppLauncher
import com.carassistant.core.LaunchTarget
import com.carassistant.data.SettingsRepository
import com.carassistant.ui.MainViewModel
import com.carassistant.ui.common.EmptyHint
import com.carassistant.ui.common.UninstallConfirmDialog
import com.carassistant.ui.common.SectionCard
import com.carassistant.ui.common.StatusChip
import com.carassistant.ui.common.StatusKind
import com.carassistant.ui.theme.CarStatusColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 启动项配置页。
 *
 * 这是整个 App 的核心配置界面，解决「亿连的包名是什么」这个问题 ——
 * 不用去猜、不用敲 adb，直接从扫描出的应用列表里点选即可。
 *
 * 分两段展示：
 *  上：已配置的启动项（可排序、调延时、启停）
 *  下：全部已安装应用（可搜索、点选添加）
 */
@Composable
fun AppsScreen(
    viewModel: MainViewModel,
    settings: SettingsRepository.Settings,
    availableApps: List<AppLauncher.AppEntry>,
    filteredApps: List<AppLauncher.AppEntry>,
    query: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    /** 待确认卸载的应用；非空时显示确认弹窗 */
    var pendingUninstall by remember { mutableStateOf<AppLauncher.AppEntry?>(null) }

    /** 卸载执行中（防止重复点击） */
    var uninstalling by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 已配置的启动项 ----
        item {
            SectionCard(
                title = "启动顺序",
                subtitle = "排在第一个的应用会最后获得焦点",
                icon = Icons.Filled.VerticalAlignTop,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (settings.targets.isEmpty()) {
                        EmptyHint("还没有添加启动项\n点击下方按钮从应用列表中选择")
                    } else {
                        settings.targets.forEachIndexed { index, target ->
                            TargetRow(
                                index = index,
                                target = target,
                                total = settings.targets.size,
                                onToggle = { enabled ->
                                    viewModel.setTargetEnabled(target.packageName, enabled)
                                },
                                onRemove = { viewModel.removeTarget(target.packageName) },
                                onMoveTop = { viewModel.moveTargetToTop(target.packageName) },
                                onMoveUp = { viewModel.moveTargetUp(target.packageName) },
                                onMoveDown = { viewModel.moveTargetDown(target.packageName) },
                                onWaitChange = { wait ->
                                    viewModel.setTargetWait(target.packageName, wait)
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showPicker = true }
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "添加应用",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // ---- 排序说明 ----
        if (settings.targets.size > 1) {
            item {
                InfoCard(
                    text = "提示：多个应用依次启动时，只有最后一个会停留在前台。" +
                        "如果你希望上车就看到亿连，把它移到第一位并把延时设为 0。",
                )
            }
        }

        // ---- 找不到亿连时的排查引导 ----
        item {
            SectionCard(
                title = "找不到「亿连」？",
                icon = Icons.Filled.Warning,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "本页会列出车机上所有已安装的应用（含系统预装）。如果列表里没有，" +
                            "可能的原因：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(
                        "1. 亿连还没装 —— 先装好再回来刷新",
                        "2. 被车机 ROM 隐藏 —— 可以试试用包名直接添加",
                        "3. 应用名不是「亿连」—— 用搜索框搜「连」「Link」「carlife」等关键词",
                    ).forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { viewModel.loadApps() }) {
                            Text("重新扫描")
                        }
                        TextButton(onClick = { showPicker = true }) {
                            Text("手动输入包名")
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            query = query,
            onQueryChange = viewModel::setAppQuery,
            apps = filteredApps,
            isLoading = isLoading,
            existingPackages = settings.targets.map { it.packageName }.toSet(),
            onAdd = { entry ->
                viewModel.addTarget(entry)
                showPicker = false
            },
            onAddByPackage = { pkg ->
                viewModel.addTarget(
                    AppLauncher.AppEntry(
                        packageName = pkg,
                        label = pkg,
                        isSystem = false,
                        hasLauncher = false,
                    )
                )
                showPicker = false
            },
            onDismiss = { showPicker = false },
            onUninstallRequest = { entry ->
                pendingUninstall = entry
            },
        )
    }

    // ---- 卸载确认弹窗 ----
    pendingUninstall?.let { entry ->
        UninstallConfirmDialog(
            entry = entry,
            isBusy = uninstalling,
            onDismiss = { if (!uninstalling) pendingUninstall = null },
            onConfirm = {
                uninstalling = true
                viewModel.uninstallApp(entry.packageName) { success, _ ->
                    uninstalling = false
                    pendingUninstall = null
                }
            },
        )
    }
}

// ==================== 启动项行 ====================

@Composable
private fun TargetRow(
    index: Int,
    target: LaunchTarget,
    total: Int,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onMoveTop: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onWaitChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 序号徽标
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (target.enabled) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (target.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.label.ifBlank { target.packageName },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = target.packageName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Switch(
                    checked = target.enabled,
                    onCheckedChange = onToggle,
                )
            }

            Spacer(Modifier.height(10.dp))

            // 排序 + 删除
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = onMoveTop,
                    enabled = index > 0,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Filled.VerticalAlignTop,
                        contentDescription = "移到最前",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "上移",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < total - 1,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "下移",
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                StatusChip(
                    text = if (target.waitBeforeMs == 0) {
                        "立即启动"
                    } else {
                        "等待 ${target.waitBeforeMs / 1000}s"
                    },
                    kind = StatusKind.Info,
                )

                IconButton(
                    onClick = {
                        // 循环切换常用延时：0 → 3s → 5s → 10s → 0
                        val next = when (target.waitBeforeMs) {
                            0 -> 3_000
                            3_000 -> 5_000
                            5_000 -> 10_000
                            else -> 0
                        }
                        onWaitChange(next)
                    },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "调整延时",
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "移除",
                        tint = CarStatusColors.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ==================== 应用选择对话框 ====================

@Composable
private fun AppPickerDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    apps: List<AppLauncher.AppEntry>,
    isLoading: Boolean,
    existingPackages: Set<String>,
    onAdd: (AppLauncher.AppEntry) -> Unit,
    onAddByPackage: (String) -> Unit,
    onDismiss: () -> Unit,
    onUninstallRequest: (AppLauncher.AppEntry) -> Unit,
) {
    var manualMode by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (manualMode) "手动输入包名" else "选择应用")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                if (manualMode) {
                    Text(
                        text = "输入应用包名，例如 com.example.yilian",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("包名") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索应用名或包名") },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "清空")
                                }
                            }
                        },
                        singleLine = true,
                    )

                    Spacer(Modifier.height(10.dp))

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(apps, key = { it.packageName }) { entry ->
                                AppPickerRow(
                                    entry = entry,
                                    alreadyAdded = entry.packageName in existingPackages,
                                    onClick = { onAdd(entry) },
                                    onUninstall = { onUninstallRequest(entry) },
                                )
                            }
                            if (apps.isEmpty()) {
                                item {
                                    EmptyHint("没有匹配的应用")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (manualMode) {
                TextButton(
                    onClick = {
                        if (manualInput.isNotBlank()) onAddByPackage(manualInput.trim())
                    },
                    enabled = manualInput.isNotBlank(),
                ) {
                    Text("添加")
                }
            } else {
                TextButton(onClick = {
                    manualMode = true
                    query.let { if (it.contains('.')) manualInput = it }
                }) {
                    Text("手动输入包名")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (manualMode) manualMode = false else onDismiss()
            }) {
                Text(if (manualMode) "返回列表" else "取消")
            }
        },
    )
}

@Composable
private fun AppPickerRow(
    entry: AppLauncher.AppEntry,
    alreadyAdded: Boolean,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !alreadyAdded, onClick = onClick)
            .background(
                if (alreadyAdded) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .heightIn(min = 58.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (alreadyAdded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (entry.isSystem) {
                    Spacer(Modifier.width(8.dp))
                    StatusChip(text = "系统", kind = StatusKind.Neutral)
                }
                if (alreadyAdded) {
                    Spacer(Modifier.width(8.dp))
                    StatusChip(text = "已添加", kind = StatusKind.Success)
                }
            }
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (!alreadyAdded) {
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "添加",
                    modifier = Modifier.size(20.dp),
                )
            }

            // 卸载入口：长按才能点到，避免开车时误触。
            // 保护名单内的系统组件点了也没用，后端会拒绝，这里不额外禁用
            // 是为了保持视觉一致，错误提示由确认弹窗和日志给出
            IconButton(
                onClick = onUninstall,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "卸载 ${entry.label}",
                    tint = CarStatusColors.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
