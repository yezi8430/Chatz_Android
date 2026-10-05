package asia.guojuice.yezigotify

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import asia.guojuice.yezigotify.ui.settings.SettingsScreen
import asia.guojuice.yezigotify.ui.theme.YezigotifyTheme
import asia.guojuice.yezigotify.utils.ThemeColorManager


class SettingsActivity : AppCompatActivity() {

    private var colorCallback: ThemeColorManager.ColorCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    YezigotifyTheme {
                        SettingsScreen(
                            onBack = { finish() },
                            onOpenDnd = {
                                startActivity(Intent(this@SettingsActivity, DndSettingsActivity::class.java))
                            }
                        )
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        colorCallback = ThemeColorManager.register { color ->
            applyStatusBarColor(color)
        }
    }

    override fun onPause() {
        super.onPause()
        colorCallback?.let { ThemeColorManager.unregister(it) }
        colorCallback = null
    }

    private fun applyStatusBarColor(color: Int) {
        window.statusBarColor = color
        val isLight = ThemeColorManager.isLightColor(color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isLight
    }
}