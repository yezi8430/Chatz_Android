package asia.guojuice.yezigotify.utils

import androidx.compose.runtime.mutableStateOf

/**
 * 全局 Compose 主题状态
 * Activity 更新它 → YezigotifyTheme 立即重组
 */
object AppThemeState {
    val followSystem = mutableStateOf(true)
    val darkMode = mutableStateOf(false)
}