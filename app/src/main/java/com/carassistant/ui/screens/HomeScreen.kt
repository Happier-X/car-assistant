package com.carassistant.ui.screens

import androidx.compose.material.icons.Icons
import com.carassistant.ui.icons.CheckCircle
import com.carassistant.ui.icons.Info
import com.carassistant.ui.icons.PlayArrow
import com.carassistant.ui.icons.Refresh
import com.carassistant.ui.icons.Warning
import com.carassistant.ui.icons.Wifi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carassistant.core.BootFlowOrchestrator
import com.carassistant.core.BootReport
import com.carassistant.core.CapabilitySnapshot
import com.carassistant.core.Permissions
import com.carassistant.data.SettingsRepository
import com.carassistant.ui.MainViewModel
import com.carassistant.ui.common.EmptyHint
import com.carassistant.ui.common.KeyValueRow
import com.carassistant.ui.common.SectionCard
import com.carassistant.ui.common.StatusChip
import com.carassistant.ui.common.StatusKind
import com.carassistant.ui.theme.CarStatusColors

/**
 * 状态页 —— 打开 App 第一眼看到的界面。
 *
 * 内容优先级（车机场景下用户只有几秒钟注意力）：
 *  1. 当前配置的一句话摘要（「开机时会做什么」）
 *  2. 两个测试按钮（出问题时能立刻验证）
 *  3. 上次执行结果
 *  4. 环境自检（是否需要额外授权）
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    capabilities: CapabilitySnapshot?,
    flowState: BootFlowOrchestrator.State,
    lastReport: BootReport?,
    settings: SettingsRepository.Settings,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 顶部摘要 ----
        item {
            SummaryCard(settings = settings, capabilities = capabilities)
        }

        // ---- 执行中进度 ----
        item {
            AnimatedVisibility(visible = flowState is BootFlowOrchestrator.State.Running) {
                RunningCard(flowState)
            }
        }

        // ---- 操作按钮 ----
        item {
            ActionCard(
                onRunNow = viewModel::runFlowNow,
                onTestWifi = viewModel::testWifiOnly,
                isRunning = flowState is BootFlowOrchestrator.State.Running,
            )
        }

        // ---- 上次结果 ----
        if (lastReport != null) {
            item {
                LastReportCard(lastReport)
            }
        }

        // ---- 环境自检 ----
        item {
            DiagnosticsCard(
                capabilities = capabilities,
                onRefresh = viewModel::refreshCapabilities,
                onFixAccessibility = {
                    viewModel.openIntent(Permissions.accessibilitySettingsIntent())
                },
                onRedetectRoot = viewModel::redetectRoot,
            )
        }

        // ---- 没有配置启动项时的引导 ----
        if (settings.targets.isEmpty()) {
            item {
                SectionCard(
                    title = "下一步",
                    subtitle = "还没有配置要自动启动的应用",
                    icon = Icons.Filled.Info,
                ) {
                    Text(
                        text = "切换到「启动项」标签页，从已安装应用列表里选中「亿连车机版」，" +
                            "它就会在每次车机启动时自动打开。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ==================== 摘要卡片 ====================

@Composable
private fun SummaryCard(
    settings: SettingsRepository.Settings,
    capabilities: CapabilitySnapshot?,
) {
    SectionCard(
        title = "开机时会做什么",
        subtitle = "当前配置摘要",
        icon = Icons.Filled.CheckCircle,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SummaryLine(
                enabled = settings.wifiEnabled,
                text = if (settings.wifiEnabled) "打开 WiFi" else "不处理 WiFi",
            )
            if (settings.launchEnabled && settings.targets.isNotEmpty()) {
                settings.targets.filter { it.enabled }.forEachIndexed { index, target ->
                    SummaryLine(
                        enabled = true,
                        text = "启动 ${target.label.ifBlank { target.packageName }}",
                        prefix = if (index == 0) "然后" else "再",
                    )
                }
            } else if (settings.launchEnabled) {
                SummaryLine(enabled = false, text = "未配置启动项")
            } else {
                SummaryLine(enabled = false, text = "不启动任何应用")
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = if (settings.triggerOnBoot) "开机触发已开" else "开机触发已关",
                    kind = if (settings.triggerOnBoot) StatusKind.Success else StatusKind.Neutral,
                )
                if (settings.triggerOnPowerConnect) {
                    StatusChip(text = "上电触发已开", kind = StatusKind.Success)
                }
            }

            capabilities?.let { caps ->
                if (!caps.wifiSilentLikelyWorks) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = CarStatusColors.warning,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "静默开 WiFi 可能失效，需开启无障碍服务兜底",
                            style = MaterialTheme.typography.labelMedium,
                            color = CarStatusColors.warning,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(enabled: Boolean, text: String, prefix: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (prefix != null) "$prefix：$text" else text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ==================== 执行中 ====================

@Composable
private fun RunningCard(state: BootFlowOrchestrator.State) {
    if (state !is BootFlowOrchestrator.State.Running) return

    SectionCard(title = "正在执行", icon = Icons.Filled.Refresh) {
        Column {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = state.currentStep,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (state.completedSteps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.completedSteps.forEach { step ->
                    Text(
                        text = "· $step",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ==================== 操作按钮 ====================

@Composable
private fun ActionCard(
    onRunNow: () -> Unit,
    onTestWifi: () -> Unit,
    isRunning: Boolean,
) {
    SectionCard(title = "立即验证", subtitle = "不用重启车机就能测试效果") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRunNow,
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("执行一次完整流程", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = onTestWifi,
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("只测试打开 WiFi", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ==================== 上次结果 ====================

@Composable
private fun LastReportCard(report: BootReport) {
    val allOk = report.launchFail == 0 && (!report.wifiRequested || report.wifiOk)

    SectionCard(
        title = "上次执行结果",
        subtitle = "耗时 ${report.durationMs} ms ｜ 触发源 ${report.trigger}",
        icon = if (allOk) Icons.Filled.CheckCircle else Icons.Filled.Warning,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (report.wifiRequested) {
                ResultLine(
                    ok = report.wifiOk,
                    text = "WiFi：${report.wifiSummary}",
                )
            }
            report.launchResults.forEach { result ->
                ResultLine(
                    ok = result.success,
                    text = "${result.label}：${if (result.success) result.strategy else "失败（${result.strategy}）"}",
                )
            }
        }
    }
}

@Composable
private fun ResultLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (ok) "✔" else "✘",
            color = if (ok) CarStatusColors.success else CarStatusColors.error,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ==================== 环境自检 ====================

@Composable
private fun DiagnosticsCard(
    capabilities: CapabilitySnapshot?,
    onRefresh: () -> Unit,
    onFixAccessibility: () -> Unit,
    onRedetectRoot: () -> Unit,
) {
    SectionCard(
        title = "环境自检",
        subtitle = "决定 WiFi 能否静默开启的关键信息",
        icon = Icons.Filled.Info,
    ) {
        if (capabilities == null) {
            EmptyHint("正在检测…")
            return@SectionCard
        }

        Column {
            KeyValueRow("车机型号", capabilities.model)
            KeyValueRow(
                "Android",
                "${capabilities.androidRelease}（API ${capabilities.sdkInt}）",
            )
            KeyValueRow(
                "应用 targetSdk",
                "${capabilities.appTargetSdk}",
                valueColor = if (capabilities.appTargetSdk < 29) {
                    CarStatusColors.success
                } else {
                    CarStatusColors.error
                },
            )
            KeyValueRow(
                "Root 权限",
                capabilities.rootDetail,
                valueColor = if (capabilities.hasRoot) {
                    CarStatusColors.success
                } else {
                    CarStatusColors.neutral
                },
            )
            KeyValueRow(
                "安全设置写入",
                if (capabilities.hasWriteSecureSettings) "已授予" else "未授予",
                valueColor = if (capabilities.hasWriteSecureSettings) {
                    CarStatusColors.success
                } else {
                    CarStatusColors.warning
                },
            )
            KeyValueRow(
                "WiFi 当前",
                if (capabilities.wifiEnabled) "已开启" else "已关闭",
            )
            KeyValueRow(
                "无障碍服务",
                if (capabilities.accessibilityEnabled) "已开启" else "未开启",
                valueColor = if (capabilities.accessibilityEnabled) {
                    CarStatusColors.success
                } else {
                    CarStatusColors.warning
                },
            )

            Spacer(Modifier.height(12.dp))

            // 根据检测结果给出针对性建议
            if (!capabilities.hasRoot && !capabilities.hasWriteSecureSettings) {
                HintBox(
                    text = "未检测到 root。如果静默开 WiFi 失败，请开启无障碍服务 —— " +
                        "助手会在需要时自动点击设置页里的 WiFi 开关。",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onFixAccessibility,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (capabilities.accessibilityEnabled) "无障碍已开启" else "开启无障碍")
                    }
                    OutlinedButton(
                        onClick = onRedetectRoot,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("重新检测 root")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onRedetectRoot,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("重新检测 root")
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("刷新")
                    }
                }
            }
        }
    }
}

@Composable
private fun HintBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CarStatusColors.warning.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = CarStatusColors.warning,
        )
    }
}