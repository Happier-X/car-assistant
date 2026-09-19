package com.carassistant.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 自带的图标集。
 *
 * ## 为什么不直接用 androidx.compose.material:material-icons-extended
 *
 * 那个库有 30MB+（debug APK 里光它就把包体从 1.8MB 撑到 19.8MB）。
 * 它把所有 Material 图标都编进了 dex，而我们只用得到 20 个。
 * 车机的存储和安装包体积都紧张，所以这里手写所需的矢量图。
 *
 * 路径数据来自 Material Symbols 官方图标集（Apache 2.0），
 * 保持 24dp viewport 与原版一致，因此视觉上与标准 Material 图标无差别。
 *
 * 用法与官方一致：`Icons.Filled.Wifi`（通过本包内的扩展属性提供）。
 */

private fun icon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 用 PathParser 解析 SVG 路径数据（比逐个 moveTo/lineTo 直观得多，
        // 也方便直接从 Material Symbols 官方图标复制路径）
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()

// ==================== 图标定义 ====================

val Icons.Filled.Wifi: ImageVector
    get() = WifiIcon

private val WifiIcon: ImageVector by lazy {
    icon(
        "Filled.Wifi",
        "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9z" +
            "M9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0z" +
            "M5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z"
    )
}

val Icons.Filled.Add: ImageVector
    get() = AddIcon

private val AddIcon: ImageVector by lazy {
    icon("Filled.Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
}

val Icons.Filled.Delete: ImageVector
    get() = DeleteIcon

private val DeleteIcon: ImageVector by lazy {
    icon(
        "Filled.Delete",
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
    )
}

val Icons.Filled.Build: ImageVector
    get() = BuildIcon

private val BuildIcon: ImageVector by lazy {
    icon(
        "Filled.Build",
        "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 " +
            "1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 " +
            "1.4,0l2.3,-2.3c0.5,-0.4 0.5,-1.1 0.1,-1.4z"
    )
}

val Icons.Filled.CheckCircle: ImageVector
    get() = CheckCircleIcon

private val CheckCircleIcon: ImageVector by lazy {
    icon(
        "Filled.CheckCircle",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
            "M10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z"
    )
}

val Icons.Filled.Clear: ImageVector
    get() = ClearIcon

private val ClearIcon: ImageVector by lazy {
    icon(
        "Filled.Clear",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 " +
            "17.59,19 19,17.59 13.41,12z"
    )
}

val Icons.Filled.Home: ImageVector
    get() = HomeIcon

private val HomeIcon: ImageVector by lazy {
    icon("Filled.Home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
}

val Icons.Filled.Info: ImageVector
    get() = InfoIcon

private val InfoIcon: ImageVector by lazy {
    icon(
        "Filled.Info",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
            "M13,17h-2v-6h2v6zM13,9h-2V7h2v2z"
    )
}

val Icons.Filled.List: ImageVector
    get() = ListIcon

private val ListIcon: ImageVector by lazy {
    icon(
        "Filled.List",
        "M3,13h2v-2L3,11v2zM3,17h2v-2L3,15v2zM3,9h2L5,7L3,7v2z" +
            "M7,13h14v-2L7,11v2zM7,17h14v-2L7,15v2zM7,7v2h14L21,7L7,7z"
    )
}

val Icons.Filled.PlayArrow: ImageVector
    get() = PlayArrowIcon

private val PlayArrowIcon: ImageVector by lazy {
    icon("Filled.PlayArrow", "M8,5v14l11,-7z")
}

val Icons.Filled.Refresh: ImageVector
    get() = RefreshIcon

private val RefreshIcon: ImageVector by lazy {
    icon(
        "Filled.Refresh",
        "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -8,8s3.58,8 8,8c3.73,0 " +
            "6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 " +
            "6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
    )
}

val Icons.Filled.Search: ImageVector
    get() = SearchIcon

private val SearchIcon: ImageVector by lazy {
    icon(
        "Filled.Search",
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 " +
            "3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5z" +
            "M9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    )
}

val Icons.Filled.Settings: ImageVector
    get() = SettingsIcon

private val SettingsIcon: ImageVector by lazy {
    icon(
        "Filled.Settings",
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
            "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96" +
            "c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84" +
            "c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33" +
            "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58" +
            "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
            "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
            "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54" +
            "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
            "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6" +
            "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
    )
}

val Icons.Filled.Warning: ImageVector
    get() = WarningIcon

private val WarningIcon: ImageVector by lazy {
    icon("Filled.Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
}

val Icons.Filled.ArrowUpward: ImageVector
    get() = ArrowUpwardIcon

private val ArrowUpwardIcon: ImageVector by lazy {
    icon("Filled.ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
}

val Icons.Filled.ArrowDownward: ImageVector
    get() = ArrowDownwardIcon

private val ArrowDownwardIcon: ImageVector by lazy {
    icon(
        "Filled.ArrowDownward",
        "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z"
    )
}

val Icons.Filled.VerticalAlignTop: ImageVector
    get() = VerticalAlignTopIcon

private val VerticalAlignTopIcon: ImageVector by lazy {
    icon("Filled.VerticalAlignTop", "M8,11h3v10h2V11h3l-4,-4 -4,4zM4,3v2h16V3H4z")
}

val Icons.Filled.PowerSettingsNew: ImageVector
    get() = PowerSettingsNewIcon

private val PowerSettingsNewIcon: ImageVector by lazy {
    icon(
        "Filled.PowerSettingsNew",
        "M13,3h-2v10h2V3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7" +
            "s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12" +
            "c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z"
    )
}