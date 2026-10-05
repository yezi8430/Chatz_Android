package asia.guojuice.yezigotify

import android.app.Application
import asia.guojuice.yezigotify.config.ServerConfigStore
import asia.guojuice.yezigotify.ui.dnd.ChannelDndManager
import asia.guojuice.yezigotify.ui.dnd.PerAppDndManager
import asia.guojuice.yezigotify.ui.dnd.DoNotDisturbManager
import asia.guojuice.yezigotify.capabilities.ServerCapabilitiesHolder
/**
 * Application 入口
 *
 *  删除 DynamicColors.applyToActivitiesIfAvailable(this)：
 *
 *   原因：和 Compose 侧的主题冲突。
 *     - Compose 侧：YezigotifyTheme 里自己从 ThemeColorManager 取色
 *       构建 Material3 ColorScheme，用户可以在设置页自定义
 *     - Android View 侧（MaterialAlertDialogBuilder、BottomSheet、
 *       PopupMenu 等系统组件）：用 Activity 的 theme
 *
 *   加上 DynamicColors 后，系统会把 Activity theme 换成 Material You
 *   从壁纸提取的色值，导致：
 *     - Compose 弹窗（用户设的主题色）
 *     - 系统弹窗（Material You 壁纸色）
 *     两者颜色不一致，体验割裂。
 *
 *   删除后：
 *     - 系统组件的颜色回到 Theme.Yezigotify 定义的值
 *     - Compose 主题仍走 ThemeColorManager
 *     - 用户开启动态取色时，ThemeColorManager 会主动更新 Compose 主题，
 *       两套色源统一
 */
class GotifyApplication : Application() {
    companion object {
        lateinit var instance: GotifyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)                 // log 输出开关

        // ⚠️ 必须排在 ServerCapabilitiesHolder.loadFromCache 之前：
        //    探测缓存要按 serverId 分桶，而 serverId 由这次迁移产出
        ServerConfigStore.migrateLegacyIfNeeded(this)

        // 把存量明文 servers_json 转成加密格式。
        // 必须排在迁移之后：迁移可能刚写出明文，这一步才能接着加密它。
        ServerConfigStore.encryptExistingIfNeeded(this)

        DoNotDisturbManager.init(this)    // 全局勿扰
        ServerCapabilitiesHolder.loadFromCache(this)//chatz检测
        PerAppDndManager.init(this)       // 每 App 独立勿扰（Gotify / ntfy）
        ChannelDndManager.init(this)      // 每频道独立勿扰（Chatz）
    }
}