package com.carassistant.ui.screens

import androidx.compose.material.icons.Icons
import com.carassistant.ui.icons.Build
import com.carassistant.ui.icons.Clear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.carassistant.ui.MainViewModel
import com.carassistant.ui.theme.CarStatusColors

/**
 * 日志页。
 *
 * 这个页面是车机场景下的**核心调试工具**。
 *
 * 原因：车机上装 APK 容易，但连 adb 抓 logcat 麻烦（要拆中控、找 USB、
 * 或者配无线调试）。所以把完整执行日志做进 UI 里，出问题让用户直接
 * 截图或复制就能定位。
 *
 * 日志按级别着色，让「哪一步失败了」一眼可见。
 */
@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    logLines: List<String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 新日志到达时自动滚到底部
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 工具栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "执行日志",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${logLines.size} 条记录 ｜ 自动滚动",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = {
                    // 用系统剪贴板服务：Compose 的 LocalClipboardManager 已废弃
                    val clipboard = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE
                    ) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "车机助手日志",
                            viewModel.copyLogToClipboard(),
                        )
                    )
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 46.dp),
            ) {
                Text("复制全部", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = viewModel::clearLog,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 46.dp),
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("清空", style = MaterialTheme.typography.labelMedium)
            }
        }

        // ---- 诊断动作 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::dumpActiveWindow,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp),
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("导出当前窗口结构", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 日志列表 ----
        if (logLines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无日志\n执行一次流程后这里会显示详细记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(logLines) { line ->
                    LogLine(line)
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val color = when {
        line.contains(" ✘/") || line.contains(" E/") -> CarStatusColors.error
        line.contains(" ✔/") || line.contains(" +/") -> CarStatusColors.success
        line.contains(" ▶/") || line.contains(" >/") -> MaterialTheme.colorScheme.primary
        line.contains(" W/") -> CarStatusColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 里程碑行加底色，方便在长日志里快速定位
    val isMilestone = line.contains("▶/STEP") || line.contains("==========")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isMilestone) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(color.copy(alpha = 0.1f))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.labelMedium.fontSize * 0.92f,
            ),
            color = color,
        )
    }
}