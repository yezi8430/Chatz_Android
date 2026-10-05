package asia.guojuice.yezigotify

import android.content.Context
import android.util.Log

object AppLog {
    private const val TAG_PREFIX = "Gotify_"

    @Volatile
    private var developerMode = false

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("gotify", Context.MODE_PRIVATE)
        developerMode = prefs.getBoolean("developer_mode", false)
        initialized = true
    }

    /**
     * 刷新日志开关状态（设置页开关变化时调用）
     * @param context 可以传入 SettingsActivity 的 this 或 applicationContext
     */
    fun refresh(context: Context) {
        val prefs = context.getSharedPreferences("gotify", Context.MODE_PRIVATE)
        developerMode = prefs.getBoolean("developer_mode", false)
        initialized = true
        // 可选：打印一条日志方便验证
        Log.d(TAG_PREFIX + "AppLog", "developerMode updated to: $developerMode")
    }

    private fun isLogEnabled(): Boolean {
        if (!initialized) return false
        return developerMode
    }

    fun d(tag: String, msg: String) {
        if (!isLogEnabled()) return
        Log.d(TAG_PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (!isLogEnabled()) return
        Log.i(TAG_PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (!isLogEnabled()) return
        Log.w(TAG_PREFIX + tag, msg)
    }

    fun e(tag: String, msg: String) {
        if (!isLogEnabled()) return
        Log.e(TAG_PREFIX + tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable) {
        if (!isLogEnabled()) return
        Log.e(TAG_PREFIX + tag, msg, t)
    }

    fun v(tag: String, msg: String) {
        if (!isLogEnabled()) return
        Log.v(TAG_PREFIX + tag, msg)
    }
}