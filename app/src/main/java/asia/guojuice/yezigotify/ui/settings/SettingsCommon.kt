package asia.guojuice.yezigotify.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import asia.guojuice.yezigotify.utils.AppThemeState
import asia.guojuice.yezigotify.utils.ThemeColorManager

@Composable
private fun cardColorInt(): Int {
    val isDark = if (AppThemeState.followSystem.value) {
        isSystemInDarkTheme()
    } else {
        AppThemeState.darkMode.value
    }
    return if (isDark) {
        ColorUtils.blendARGB(android.graphics.Color.WHITE, android.graphics.Color.BLACK, 0.8f)
    } else {
        // 你已改成 0.10f
        ColorUtils.blendARGB(android.graphics.Color.WHITE, android.graphics.Color.BLACK, 0.10f)
    }
}

@Composable
fun settingsCardColor(): Color = Color(cardColorInt())

@Composable
fun settingsCardOnColor(): Color =
    Color(ThemeColorManager.contrastTextColor(cardColorInt()))

@Composable
fun settingsCardOnColorSecondary(): Color =
    settingsCardOnColor().copy(alpha = 0.7f)

/**
 * 设置页所有 OutlinedTextField 的统一圆角
 * 用法：`shape = settingsTextFieldShape()`
 */
@Composable
fun settingsTextFieldShape(): Shape = RoundedCornerShape(16.dp)

@Composable
fun settingsTextFieldColors(): TextFieldColors {
    val onCard = settingsCardOnColor()
    val muted = onCard.copy(alpha = 0.6f)
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = onCard,
        unfocusedTextColor = onCard,
        focusedBorderColor = onCard,
        unfocusedBorderColor = onCard.copy(alpha = 0.5f),
        focusedLabelColor = onCard,
        unfocusedLabelColor = onCard.copy(alpha = 0.7f),
        cursorColor = onCard,
        focusedPlaceholderColor = muted,
        unfocusedPlaceholderColor = muted,
        focusedTrailingIconColor = onCard,
        unfocusedTrailingIconColor = onCard.copy(alpha = 0.7f),
    )
}

/**
 * Switch 颜色
 *
 *  thumb 混色方向根据深色模式自动切换：
 *   浅色模式：thumb = 主题色混黑 50%（下沉感）
 *   深色模式：thumb = 主题色混白 50%（浮起感）
 *
 *   为什么不能统一混黑：
 *     深色模式下整体色调偏暗，thumb 再往下压就"沉"进背景里了，
 *     视觉上分不清 thumb 和 track。混白让 thumb 亮于 track，
 *     和深色模式"元素越靠上越亮"的视觉规律一致。
 *
 *   为什么不直接判亮度自适应：
 *     亮度判断会让"浅色主题 + 浅色模式"和"浅色主题 + 深色模式"
 *     表现不一致（同一个主题色在不同模式下 thumb 色反了），
 *     用"跟随深浅模式"更符合用户直觉，也跟整个 App 的配色逻辑一致。
 */
@Composable
fun settingsSwitchColors(): SwitchColors {
    val themeColorInt = ThemeColorManager.getEffectiveColor()
    val themeColor = Color(themeColorInt)

    // 判断当前是否深色模式（与 cardColorInt 逻辑一致）
    val isDark = if (AppThemeState.followSystem.value) {
        isSystemInDarkTheme()
    } else {
        AppThemeState.darkMode.value
    }

    // thumb 混色方向：深色混白、浅色混黑
    val thumbMixColor = if (isDark) {
        android.graphics.Color.WHITE
    } else {
        android.graphics.Color.BLACK
    }

    // 边框永远混黑 12%：边缘描深一点让轮廓更清晰
    // 深色模式下如果也混白，边框会太亮，反而抢眼
    val borderMixColor = android.graphics.Color.BLACK

    val thumbColor = Color(
        ColorUtils.blendARGB(
            themeColorInt,
            thumbMixColor,
            0.30f
        )
    )

    val borderColor = Color(
        ColorUtils.blendARGB(
            themeColorInt,
            borderMixColor,
            0.12f
        )
    )

    return SwitchDefaults.colors(
        checkedThumbColor = thumbColor,
        checkedTrackColor = themeColor,
        checkedBorderColor = borderColor,
        checkedIconColor = thumbColor,
    )
}
