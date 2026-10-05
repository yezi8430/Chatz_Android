package asia.guojuice.yezigotify.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import asia.guojuice.yezigotify.R
import asia.guojuice.yezigotify.utils.ThemeColorManager
import asia.guojuice.yezigotify.utils.AppThemeState

@Composable
fun YezigotifyTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    var seedColor by remember { mutableStateOf(ThemeColorManager.getEffectiveColor()) }

    DisposableEffect(Unit) {
        val callback = ThemeColorManager.ColorCallback { newColor ->
            seedColor = newColor
        }
        ThemeColorManager.register(callback)
        onDispose { ThemeColorManager.unregister(callback) }
    }

    //  改为读取全局 state，切换深色模式时实时响应
    val isDark = if (AppThemeState.followSystem.value) {
        isSystemInDarkTheme()
    } else {
        AppThemeState.darkMode.value
    }

    val colorScheme = remember(seedColor, isDark, context) {
        buildColorScheme(context, seedColor, isDark)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

/**
 * 用主题色构建 Material3 的 ColorScheme
 *
 * 关键：
 *   1. 用 Activity Context 读 colors.xml，才能拿到 values-night 下的深色值
 *   2.  补齐 surfaceContainer* 系列 token，避免下拉菜单/底部弹窗
 *      回落到 Material3 基线色板（浅色下是"淡紫粉"，跟你主题不搭）
 *
 * surfaceContainer* 的用途（Material3 1.2+）：
 *   - surfaceContainerLowest  → 最浅的抬升背景（很少用到）
 *   - surfaceContainerLow     → ModalBottomSheet 背景
 *   - surfaceContainer        → DropdownMenu / ExposedDropdownMenu 背景
 *   - surfaceContainerHigh    → 部分对话框、抽屉
 *   - surfaceContainerHighest → NavigationBar 等
 *
 * 本项目的策略：全部映射到 bgCard，保持整个 App 的容器色统一。
 * 如果未来想区分层级，改这里用 ColorUtils.blendARGB 微调即可。
 */
private fun buildColorScheme(
    context: Context,
    seedColor: Int,
    isDark: Boolean
): ColorScheme {
    // 强调色：浅色模式变暗，深色模式变亮，保证在 surface 上对比度足够
    val accentInt = if (isDark) {
        ColorUtils.blendARGB(seedColor, android.graphics.Color.WHITE, 0.3f)
    } else {
        ColorUtils.blendARGB(seedColor, android.graphics.Color.BLACK, 0.3f)
    }

    val primary = Color(accentInt)
    val onPrimary = Color(ThemeColorManager.contrastTextColor(accentInt))
    val primaryContainer = Color(seedColor)
    val onPrimaryContainer = Color(ThemeColorManager.contrastTextColor(seedColor))

    //  用 Activity Context 读颜色，才能拿到 values-night 里的深色值
    val bgPage = Color(ContextCompat.getColor(context, R.color.bg_page))
    val bgCard = Color(ContextCompat.getColor(context, R.color.bg_card))
    val textPrimaryRes = Color(ContextCompat.getColor(context, R.color.text_primary))
    val textSecondaryRes = Color(ContextCompat.getColor(context, R.color.text_secondary))

    //  层级色全部映射到 bgCard
    //
    // 为什么不区分 High / Low？
    //   本项目只有 bgCard 一种卡片色，没有多层色调需求。
    //   全部映射到同一个值，比"分开微调"更容易保持视觉一致。
    val surfaceContainerLowest = bgCard
    val surfaceContainerLow = bgCard
    val surfaceContainer = bgCard
    val surfaceContainerHigh = bgCard
    val surfaceContainerHighest = bgCard

    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            background = bgPage,
            onBackground = textPrimaryRes,
            surface = bgCard,
            onSurface = textPrimaryRes,
            surfaceVariant = bgCard,
            onSurfaceVariant = textSecondaryRes,
            outline = textSecondaryRes,
            //  新增：层级色
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            background = bgPage,
            onBackground = textPrimaryRes,
            surface = bgCard,
            onSurface = textPrimaryRes,
            surfaceVariant = bgCard,
            onSurfaceVariant = textSecondaryRes,
            outline = textSecondaryRes,
            //  新增：层级色
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
        )
    }
}