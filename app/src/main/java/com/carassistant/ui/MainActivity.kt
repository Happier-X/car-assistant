package com.carassistant.ui

import androidx.compose.material.icons.Icons
import com.carassistant.ui.icons.Build
import com.carassistant.ui.icons.Delete
import com.carassistant.ui.icons.Home
import com.carassistant.ui.icons.List
import com.carassistant.ui.icons.Settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carassistant.ui.screens.AppsScreen
import com.carassistant.ui.screens.HomeScreen
import com.carassistant.ui.screens.LogsScreen
import com.carassistant.ui.screens.ManageScreen
import com.carassistant.ui.screens.SettingsScreen
import com.carassistant.ui.theme.CarAssistantTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 主界面。
 *
 * 用 Compose 而非 XML：车机 UI 需要大量状态驱动的动态展示
 * （实时日志、执行进度、能力自检结果），声明式写法的维护成本低得多。
 *
 * 界面结构刻意保持扁平 —— 4 个标签页，没有深层导航。
 * 车机上用户注意力极短，深层菜单是灾难。
 */
class MainActivity : ComponentActivity() {

    /**
     * 用一个可空字段持有 ViewModel。
     *
     * 之所以不用 `by viewModels()`：MainViewModel 需要 ApplicationContext，
     * 必须自定义 Factory；而 Compose 侧的 `viewModel(factory = ...)` 与这里的
     * ViewModelProvider 会共用同一个 ViewModelStore，
     * 所以两处必须传同一个 factory，否则会出现 "反射调用无参构造失败" 的崩溃。
     */
    private val mainViewModel: MainViewModel by lazy {
        ViewModelProvider(
            this,
            MainViewModelFactory.create(),
        )[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边：车机屏幕比例特殊，充分利用每一像素
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            CarAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CarAssistantRoot(viewModel = mainViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时权限状态可能已变，刷新自检。
        // 注意必须用带 Factory 的引用，否则会走默认工厂反射无参构造而崩溃
        mainViewModel.refreshCapabilities()
    }
}

@Composable
private fun CarAssistantRoot(
    viewModel: MainViewModel,
) {
    var selectedTab by remember { mutableStateOf(AppTab.Home) }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val flowState by viewModel.flowState.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastReport.collectAsStateWithLifecycle()
    val logLines by viewModel.logLines.collectAsStateWithLifecycle()
    val availableApps by viewModel.availableApps.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val appQuery by viewModel.appQuery.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                AppTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        AppTab.Home -> Icons.Filled.Home
                        AppTab.Apps -> Icons.Filled.List
                        AppTab.Manage -> Icons.Filled.Delete
                        AppTab.Settings -> Icons.Filled.Settings
                        AppTab.Logs -> Icons.Filled.Build
                    }
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(icon, contentDescription = tab.label) },
                        label = {
                            Text(
                                text = if (tab == AppTab.Apps && settings.targets.isNotEmpty()) {
                                    "${tab.label} (${settings.targets.size})"
                                } else {
                                    tab.label
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (selectedTab) {
            AppTab.Home -> HomeScreen(
                viewModel = viewModel,
                capabilities = capabilities,
                flowState = flowState,
                lastReport = lastReport,
                settings = settings,
                modifier = contentModifier,
            )

            AppTab.Apps -> AppsScreen(
                viewModel = viewModel,
                settings = settings,
                availableApps = availableApps,
                filteredApps = filteredApps,
                query = appQuery,
                isLoading = isLoadingApps,
                modifier = contentModifier,
            )

            AppTab.Manage -> ManageScreen(
                viewModel = viewModel,
                availableApps = availableApps,
                filteredApps = filteredApps,
                query = appQuery,
                isLoading = isLoadingApps,
                modifier = contentModifier,
            )

            AppTab.Settings -> SettingsScreen(
                viewModel = viewModel,
                settings = settings,
                capabilities = capabilities,
                modifier = contentModifier,
            )

            AppTab.Logs -> LogsScreen(
                viewModel = viewModel,
                logLines = logLines,
                modifier = contentModifier,
            )
        }
    }
}

enum class AppTab(val label: String) {
    Home("状态"),
    Apps("启动项"),
    Manage("管理"),
    Settings("设置"),
    Logs("日志"),
}