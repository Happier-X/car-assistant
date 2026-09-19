package com.carassistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 车机配色。
 *
 * 设计取向和手机 App 完全不同：
 *  - 默认深色：车内夜间驾驶，亮底屏幕会严重影响视线
 *  - 高对比度：车机屏幕在强光下可视性差，且驾驶员只能瞥一眼
 *  - 大号字体与热区：颠簸路面手指定位精度低
 */
private val CarPrimary = Color(0xFF4DA3FF)
private val CarSuccess = Color(0xFF4CD964)
private val CarWarning = Color(0xFFFFB020)
private val CarError = Color(0xFFFF453A)

private val DarkColors = darkColorScheme(
    primary = CarPrimary,
    onPrimary = Color(0xFF00182E),
    primaryContainer = Color(0xFF0B3D66),
    onPrimaryContainer = Color(0xFFD3E7FF),
    secondary = Color(0xFF8FB8D9),
    onSecondary = Color(0xFF0C2337),
    tertiary = CarSuccess,
    background = Color(0xFF101418),
    onBackground = Color(0xFFE4E7EA),
    surface = Color(0xFF171C21),
    onSurface = Color(0xFFE4E7EA),
    surfaceVariant = Color(0xFF22282E),
    onSurfaceVariant = Color(0xFFB6BEC6),
    outline = Color(0xFF3C444C),
    error = CarError,
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF5C0F0B),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00629E),
    onPrimary = Color.White,
    secondary = Color(0xFF4C6172),
    tertiary = Color(0xFF2E7D32),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFDDE3E8),
    error = Color(0xFFBA1A1A),
)

/** 语义色：成功/警告状态用,不参与 Material 的角色体系 */
object CarStatusColors {
    val success = CarSuccess
    val warning = CarWarning
    val error = CarError
    val neutral = Color(0xFF8A939B)
}

@Composable
fun CarAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme() || true, // 车机默认走深色
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Android 15+ 起 statusBarColor/navigationBarColor 已废弃（强制边到边）。
            // 透明状态栏由 Activity 侧的 enableEdgeToEdge() 统一处理，这里只负责
            // 低版本上让内容延伸到系统栏底下。
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CarTypography,
        content = content,
    )
}