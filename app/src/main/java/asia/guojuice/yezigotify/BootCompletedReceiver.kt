package asia.guojuice.yezigotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import asia.guojuice.yezigotify.config.ServerConfigStore

/**
 * 开机自启 Receiver
 *
 *  新增 LOCKED_BOOT_COMPLETED 处理：
 *
 *   背景：
 *     - 传统 BOOT_COMPLETED 只在**用户解锁后**才发出
 *     - LOCKED_BOOT_COMPLETED 在设备**刚启动完、用户尚未解锁**时发出
 *
 *   为什么加了 directBootAware 还要判断 isUserUnlocked：
 *     - directBootAware 让 Receiver 在解锁前也能被系统调起
 *     - 但 App 的 SharedPreferences("gotify") 默认在**凭据加密存储**里，
 *       解锁前读不到任何值（会拿到默认空串）
 *     - 所以解锁前拿到 intent 时，只能"占个位"等解锁，不能真的启动 Service
 *     - 用户解锁后系统会再发一次 BOOT_COMPLETED，那时凭据存储可读，
 *       才真正启动 Service
 *
 *   收益：
 *     部分 ROM 对 BOOT_COMPLETED 有延迟（解锁后才补发），加了这个让
 *     App 在刚启动就"知道"自己要被启动了；将来若把配置迁移到设备加密
 *     存储（device-protected storage），就能真正做到"未解锁即启动服务"。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    private val tag = "BootCompletedReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // 设备已启动，但用户可能还没解锁
                val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                val unlocked = userManager?.isUserUnlocked ?: true

                if (!unlocked) {
                    AppLog.d(tag, "🔒 设备已启动但未解锁，跳过（等 BOOT_COMPLETED）")
                    return
                }
                AppLog.d(tag, "🔓 设备已启动且已解锁，处理")
                startServiceIfConfigured(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                AppLog.d(tag, "📱 开机完成，检查并启动 GotifyService")
                startServiceIfConfigured(context)
            }
        }
    }

    private fun startServiceIfConfigured(context: Context) {
        if (ServerConfigStore.hasAnyEnabled(context)) {
            AppLog.d(tag, " 配置完整，启动 Service")
            // 配置已由 ServerConfigStore 统一管理，Service 自行读取，不再传 extra
            val serviceIntent = Intent(context, GotifyService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            AppLog.d(tag, "⚠️ 未配置服务器，不启动")
        }
    }
}