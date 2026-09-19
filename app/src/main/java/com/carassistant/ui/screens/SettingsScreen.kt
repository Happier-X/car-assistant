package com.carassistant.ui.screens

import androidx.compose.material.icons.Icons
import com.carassistant.ui.icons.Build
import com.carassistant.ui.icons.Info
import com.carassistant.ui.icons.PowerSettingsNew
import com.carassistant.ui.icons.Wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carassistant.core.CapabilitySnapshot
import com.carassistant.core.Permissions
import com.carassistant.data.SettingsRepository
import com.carassistant.ui.MainViewModel
import com.carassistant.ui.common.SectionCard
import com.carassistant.ui.common.SwitchRow

/**
 * 设置页。
 *
 * 按「功能域」分组，每组都有说明文字 —— 车机上的选项如果看不懂，
 * 用户唯一的办法就是全部打开试一遍，那会导致很多诡异问题。
 * 所以每个开关都写清「开了会怎样、关了会怎样」。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    settings: SettingsRepository.Settings,
    capabilities: CapabilitySnapshot?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ==================== 触发时机 ====================
        item {
            SectionCard(
                title = "触发时机",
                subtitle = "什么时候执行这套流程",
                icon = Icons.Filled.PowerSettingsNew,
            ) {
                Column {
                    SwitchRow(
                        title = "车机启动时",
                        description = "熄火重启、车机冷启动后自动执行（最常用）",
                        checked = settings.triggerOnBoot,
                        onCheckedChange = viewModel::setTriggerOnBoot,
                    )
                    SwitchRow(
                        title = "接通电源时",
                        description = "部分车机熄火不断电，只能靠上电事件触发",
                        checked = settings.triggerOnPowerConnect,
                        onCheckedChange = viewModel::setTriggerOnPowerConnect,
                    )
                    SwitchRow(
                        title = "屏幕点亮时",
                        description = "⚠ 每次亮屏都会执行，可能造成重复启动。仅在启动触发不灵时开启",
                        checked = settings.triggerOnScreenOn,
                        onCheckedChange = viewModel::setTriggerOnScreenOn,
                    )
                }
            }
        }

        // ==================== 启动延时 ====================
        item {
            SectionCard(
                title = "启动延时",
                subtitle = "当前 ${settings.bootDelayMs / 1000} 秒",
                icon = Icons.Filled.Build,
            ) {
                Column {
                    Text(
                        text = "车机刚开机时系统服务还没就绪，立即执行容易失败。" +
                            "如果日志里看到失败，把这个值调大一些。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 3_000, 5_000, 8_000, 15_000, 30_000).forEach { value ->
                            FilterChip(
                                selected = settings.bootDelayMs == value,
                                onClick = { viewModel.setBootDelayMs(value) },
                                label = {
                                    Text(if (value == 0) "不等待" else "${value / 1000}s")
                                },
                            )
                        }
                    }
                }
            }
        }

        // ==================== WiFi ====================
        item {
            SectionCard(
                title = "WiFi 控制",
                subtitle = "启动时自动打开 WiFi",
                icon = Icons.Filled.Wifi,
            ) {
                Column {
                    SwitchRow(
                        title = "启动时打开 WiFi",
                        description = "关闭后只执行应用启动",
                        checked = settings.wifiEnabled,
                        onCheckedChange = viewModel::setWifiEnabled,
                    )
                    SwitchRow(
                        title = "允许自动点击设置页",
                        description = "静默方式失效时，拉起系统设置页由无障碍服务代点开关",
                        checked = settings.wifiUiFallback,
                        onCheckedChange = viewModel::setWifiUiFallback,
                        enabled = settings.wifiEnabled,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "WiFi 复查等待：${settings.wifiVerifyTimeoutMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1_000, 2_500, 5_000, 8_000).forEach { value ->
                            FilterChip(
                                selected = settings.wifiVerifyTimeoutMs == value,
                                onClick = { viewModel.setWifiVerifyTimeoutMs(value) },
                                label = { Text("${value / 1000}s") },
                            )
                        }
                    }
                }
            }
        }

        // ==================== 应用启动 ====================
        item {
            SectionCard(
                title = "应用启动",
                subtitle = "启动目标应用的行为",
                icon = Icons.Filled.Build,
            ) {
                Column {
                    SwitchRow(
                        title = "启动时打开应用",
                        description = "关闭后只执行 WiFi 开启",
                        checked = settings.launchEnabled,
                        onCheckedChange = viewModel::setLaunchEnabled,
                    )
                    SwitchRow(
                        title = "启动结果校验",
                        description = "启动后确认应用真的到前台了才判定成功。" +
                            "冷启动慢的车机可关闭，避免误判为失败而重复启动",
                        checked = settings.verifyLaunch,
                        onCheckedChange = viewModel::setVerifyLaunch,
                        enabled = settings.launchEnabled,
                    )
                }
            }
        }

        // ==================== 高级 ====================
        item {
            SectionCard(
                title = "高级",
                subtitle = "一般不需要改动",
                icon = Icons.Filled.Info,
            ) {
                Column {
                    SwitchRow(
                        title = "使用 root 权限",
                        description = "检测到 root 时，用系统命令提升开关 WiFi 与启动应用的" +
                            "成功率。没有 root 时此开关无影响",
                        checked = settings.allowRoot,
                        onCheckedChange = viewModel::setAllowRoot,
                    )
                }
            }
        }

        // ==================== 权限入口 ====================
        item {
            SectionCard(
                title = "权限与系统设置",
                subtitle = "排查问题时常用",
                icon = Icons.Filled.Info,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionButton(
                        label = if (capabilities?.accessibilityEnabled == true) {
                            "无障碍服务：已开启"
                        } else {
                            "开启无障碍服务（启动兜底）"
                        },
                        onClick = {
                            viewModel.openIntent(Permissions.accessibilitySettingsIntent())
                        },
                    )
                    PermissionButton(
                        label = if (capabilities?.overlayGranted == true) {
                            "悬浮窗权限：已授予"
                        } else {
                            "授予悬浮窗权限"
                        },
                        onClick = {
                            viewModel.openIntent(Permissions.overlaySettingsIntent(context))
                        },
                    )
                    PermissionButton(
                        label = if (capabilities?.batteryOptimizationIgnored == true) {
                            "电池优化：已加入白名单"
                        } else {
                            "加入电池优化白名单（防止流程被中断）"
                        },
                        onClick = {
                            viewModel.openIntent(Permissions.batterySettingsIntent(context))
                        },
                    )
                    PermissionButton(
                        label = "打开应用详情页",
                        onClick = {
                            viewModel.openIntent(Permissions.appDetailsIntent(context))
                        },
                    )
                }
            }
        }

        // ==================== 关于 ====================
        item {
            SectionCard(
                title = "关于",
                subtitle = "车机助手 1.0.0",
                icon = Icons.Filled.Info,
            ) {
                Text(
                    text = "为雪佛兰科鲁泽 2024 款车机设计：开机自动打开 WiFi，" +
                        "并拉起「亿连车机版」等指定应用。\n\n" +
                        "实现上采用了多级降级策略 —— 不同车机的 Android 版本和 ROM 差异很大，" +
                        "单一方案必然在某些车上失效。所有策略在「日志」页都有完整记录。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}