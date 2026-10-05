package asia.guojuice.yezigotify.utils

import android.app.Activity
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import asia.guojuice.yezigotify.GotifyApplication
import java.lang.ref.WeakReference

/**
 * 统一主题色管理器
 */
object ThemeColorManager {

    private const val KEY_COLOR = "top_bar_color"
    private const val KEY_USE_DYNAMIC = "use_dynamic_color"

    /**
     *  默认颜色：绿色
     *
     * 保持与 MainActivity.initDefaults 里 `Color.parseColor("#A5D6A7")` 一致，
     * 避免"两个默认值"让读代码的人困惑（原来这里是紫色 0xFF6200EE，
     * 但 initDefaults 已经写了绿色，实际永远读不到这个默认值）。
     */
    private const val DEFAULT_COLOR = 0xFFA5D6A7.toInt()

    private val prefs: SharedPreferences by lazy {
        GotifyApplication.instance.getSharedPreferences("gotify", 0)
    }

    var currentColor: Int = DEFAULT_COLOR
        private set

    private val callbacks = mutableListOf<ColorCallback>()
    private val lock = Any()
    private var initialized = false

    // ============ 壁纸监听（广播方式，兼容性最好） ============
    private var wallpaperReceiver: BroadcastReceiver? = null
    private var activityRef: WeakReference<Activity>? = null

    fun interface ColorCallback {
        fun onColorChanged(color: Int)
    }

    private fun ensureInit() {
        if (initialized) return
        initialized = true

        if (!isDynamicColorEnabled()) {
            currentColor = prefs.getInt(KEY_COLOR, DEFAULT_COLOR)
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_COLOR) {
            if (!isDynamicColorEnabled()) {
                currentColor = prefs.getInt(KEY_COLOR, DEFAULT_COLOR)
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() {
        synchronized(lock) {
            callbacks.toList().forEach { it.onColorChanged(getEffectiveColor()) }
        }
    }

    // ==================== 动态取色 ====================

    fun isDynamicColorEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            prefs.getBoolean(KEY_USE_DYNAMIC, false)
        } else false
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_DYNAMIC, enabled).apply()
    }

    /**
     * 【唯一权威取色入口】主动从系统取当前壁纸颜色
     */
    fun updateDynamicColorFromWallpaper(activity: Activity) {
        if (!isDynamicColorEnabled()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val newColor: Int? = readColorViaWallpaperManager(activity)

        if (newColor != null && newColor != currentColor) {
            currentColor = newColor
            notifyListeners()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun readColorViaWallpaperManager(activity: Activity): Int? {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(activity)
            val colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            colors?.primaryColor?.let { c ->
                Color.argb(
                    (c.alpha() * 255f + 0.5f).toInt(),
                    (c.red() * 255f + 0.5f).toInt(),
                    (c.green() * 255f + 0.5f).toInt(),
                    (c.blue() * 255f + 0.5f).toInt()
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun updateDynamicColorFromActivity(context: Context) {
        if (isDynamicColorEnabled()) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
            val resolvedColor = typedValue.data

            currentColor = resolvedColor
            notifyListeners()
        }
    }

    fun getEffectiveColor(): Int = currentColor

    fun updateColorAndNotify() {
        ensureInit()
        if (!isDynamicColorEnabled()) {
            currentColor = prefs.getInt(KEY_COLOR, DEFAULT_COLOR)
        }
        notifyListeners()
    }

    // ==================== 广播监听（核心） ====================

    fun registerWallpaperListener(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        unregisterWallpaperListener(activity)

        activityRef = WeakReference(activity)

        wallpaperReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_WALLPAPER_CHANGED) {
                    if (isDynamicColorEnabled()) {
                        activityRef?.get()?.let { act ->
                            if (!act.isDestroyed && !act.isFinishing) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!act.isDestroyed && !act.isFinishing) {
                                        updateDynamicColorFromWallpaper(act)
                                    }
                                }, 1000)
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
        activity.registerReceiver(wallpaperReceiver, filter)
    }

    fun unregisterWallpaperListener(context: Context) {
        wallpaperReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            wallpaperReceiver = null
            activityRef = null
        }
    }

    // ==================== 注册 / 注销 ====================

    fun register(callback: ColorCallback): ColorCallback {
        ensureInit()
        synchronized(lock) { callbacks.add(callback) }
        callback.onColorChanged(getEffectiveColor())
        return callback
    }

    fun unregister(callback: ColorCallback) {
        synchronized(lock) { callbacks.remove(callback) }
    }

    fun refresh() {
        ensureInit()
        notifyListeners()
    }

    // ==================== 工具方法 ====================

    fun isLightColor(color: Int = getEffectiveColor()): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r * 0.299 + g * 0.587 + b * 0.114) > 186
    }

    fun contrastTextColor(color: Int = getEffectiveColor()): Int =
        if (isLightColor(color)) Color.BLACK else Color.WHITE
}