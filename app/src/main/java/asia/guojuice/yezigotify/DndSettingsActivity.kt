package asia.guojuice.yezigotify

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import asia.guojuice.yezigotify.ui.dnd.DndSettingsScreen
import asia.guojuice.yezigotify.ui.theme.YezigotifyTheme
import asia.guojuice.yezigotify.utils.ThemeColorManager

class DndSettingsActivity : AppCompatActivity() {

    private var statusBarColorCallback: ThemeColorManager.ColorCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            ComposeView(this).apply {
                setContent {
                    YezigotifyTheme {
                        DndSettingsScreen(onBack = { finish() })
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        statusBarColorCallback = ThemeColorManager.register { color ->
            applyStatusBarColor(color)
        }
    }

    override fun onPause() {
        super.onPause()
        statusBarColorCallback?.let { ThemeColorManager.unregister(it) }
        statusBarColorCallback = null
    }

    private fun applyStatusBarColor(color: Int) {
        window.statusBarColor = color
        val isLight = ThemeColorManager.isLightColor(color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isLight
    }
}