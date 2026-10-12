package asia.guojuice.yezigotify.utils

import android.content.Context
import asia.guojuice.yezigotify.AppIconCache
import asia.guojuice.yezigotify.config.ServerConfigStore

/**
 * 消息封面地址的「最后一公里」：相对路径 → 绝对地址
 *
 * 服务端只存**相对路径**（`/attachments/cached-xxxx.jpg`、`/icons/xxx.png`），
 * 因为它不知道自己的对外域名 —— 换域名 / 局域网 IP 都不该把消息里存死的地址搞坏。
 * 所以补全这一步在客户端做，和图标同一套做法（[AppIconCache.resolveUrl]）。
 *
 * 为什么要有这个对象：`MessageText` 只负责"该用哪个地址"（纯函数、可单测），
 * 拼域名需要 Context，两者分开才不会把单测拖进 Android 依赖。
 * 调用方从 2026-10-12 起**都必须过这里**，不然「缓存封面图」抓下来的相对路径
 * 会被图片加载器当成无效地址丢掉（实测：安卓端封面整个不显示、图片置底的样式也不生效）。
 */
object MessageImage {

    /**
     * @param serverId 消息来源服务器的 id（`GotifyMessage.sourceServerId` / `MessageEntity.serverId`）
     * @param url      [MessageText] 系列返回的地址，可能是相对路径
     * @return 可直接喂给图片加载器的绝对地址；拿不到服务器地址时返回 null（宁可不出图，也不出坏图）
     */
    fun resolve(context: Context, serverId: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url

        // ① 优先问图标缓存：它的桶在连接时就写好了 baseUrl，且天然按服务器区分
        AppIconCache.resolveUrl(serverId, url)?.let { return it }

        // ② 冷启动早期桶可能还没建好 → 回落服务器配置（同步读 prefs，不涉及 IO 等待）
        val raw = ServerConfigStore.byId(context, serverId)?.url.orEmpty().trim()
        if (raw.isBlank()) return null
        val absolute = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val base = absolute.trimEnd('/')
        return if (url.startsWith("/")) base + url else "$base/$url"
    }
}
