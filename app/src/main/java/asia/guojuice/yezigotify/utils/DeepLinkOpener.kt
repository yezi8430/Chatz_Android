package asia.guojuice.yezigotify.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import asia.guojuice.yezigotify.AppLog

/**
 * 打开消息里的深链：**能唤起 Jellyfin App 直达那一集就唤起，不行回落浏览器**
 *
 * 现状（2026-10-12 核对过两边的 AndroidManifest 与源码）：
 *   · **手机版**（`org.jellyfin.mobile`）**没有** ACTION_VIEW 过滤器 ⇒ 深链它收不到，
 *     只能开网页版详情页（浏览器里照样能播）。所以这里**不会**去唤起手机版 ——
 *     用显式组件硬拉它的 MainActivity 能打开 App，但会停在首页（data 被忽略），
 *     比"直接开网页详情页"更差。
 *   · **Android TV 版**（`org.jellyfin.androidtv`）支持：StartupActivity 的 intent-filter
 *     里就写着 ACTION_VIEW，源码里也是 `action == ACTION_VIEW && data != null ->
 *     data.toString()` 当 itemId，然后 `Destinations.itemDetails(itemId)`。
 *
 * 所以这里**运行时探测**（`queryIntentActivities`）：装上且支持就走 App，否则浏览器兜底 ——
 * 不会出现"点了没反应"；万一两边都不行，失败原因会带回给调用方（见 [openWithReason]）。
 *
 * ⚠️ ATV 的 StartupActivity 只声明了 action、**没有 `<data>`**，而"过滤器没写 data 时
 *    带 data 的 Intent 不匹配"—— 所以隐式 Intent 过不了匹配，**必须用显式组件**。
 */
object DeepLinkOpener {

    private const val ATV_PACKAGE = "org.jellyfin.androidtv"
    private const val ATV_ACTIVITY = "org.jellyfin.androidtv.ui.startup.StartupActivity"

    /** 条目 id 的形态：Jellyfin 的 N 格式 GUID（32 位十六进制） */
    private val ID_REGEX = Regex("[0-9a-fA-F]{32}")

    /**
     * 构造"打开这个深链"的 Intent（调用方自己 startActivity / 塞进 PendingIntent）
     *
     * 通知栏那种要提前构造 PendingIntent 的场景也用它 —— 探测结果就是点击时的结果。
     */
    fun intentFor(context: Context, url: String): Intent {
        jellyfinIntentFor(context, url)?.let { return it }
        return browserIntent(url)
    }

    /**
     * @return true = 已交给某个应用处理；false = 连浏览器都打不开（调用方自己提示用户）
     */
    fun open(context: Context, url: String): Boolean = openWithReason(context, url) == null

    /**
     * 同 [open]，但**把失败原因带出来**。
     *
     * 为什么要专门给个方法：手机上"点一下没反应/弹提示"这种情况，日志要先开开关、
     * 再去 logcat 里翻；把原因直接交给调用方塞进 Toast，用户一眼就能报给我们。
     *
     * @return null = 成功；非 null = 失败原因（简短、可读）
     */
    fun openWithReason(context: Context, url: String): String? {
        jellyfinIntentFor(context, url)?.let { intent ->
            try {
                context.startActivity(intent)
                return null
            } catch (e: Exception) {
                // 唤起 Jellyfin 失败不算致命：继续走浏览器兜底
                AppLog.w("DeepLinkOpener", "唤起 Jellyfin 失败，回落浏览器: ${e.message}")
            }
        }
        return try {
            context.startActivity(browserIntent(url))
            null
        } catch (e: Exception) {
            AppLog.w("DeepLinkOpener", "打开链接失败: $url —— ${e.message}")
            "${e.javaClass.simpleName}: ${e.message ?: "无详情"}"
        }
    }

    /**
     * 能给 Jellyfin App 就给它（目前只有 TV 端支持），否则 null
     *
     * ⚠️ 探测用 `queryIntentActivities`（flags=0）：`resolveActivity(Intent)` 那个重载
     *    带 MATCH_DEFAULT_ONLY，而 ATV 的 StartupActivity 声明的是 LAUNCHER/LEANBACK_LAUNCHER
     *    **没有 DEFAULT** —— 用它探测会永远返回 null（踩过）。
     * ⚠️ 过滤器的 action 有 VIEW 但**没写 `<data>`**，所以必须用显式组件（setClassName）。
     */
    private fun jellyfinIntentFor(context: Context, url: String): Intent? {
        val itemId = jellyfinItemIdOf(url) ?: return null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(ATV_PACKAGE, ATV_ACTIVITY)
            data = Uri.parse(itemId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = runCatching {
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(false)
        return if (canHandle) intent else null
    }

    private fun browserIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 从 Jellyfin 详情页地址里抠出条目 id
     *
     * 插件的地址形态是 `https://host/web/#/details?id=<guid>` —— id 在 **fragment** 里，
     * 所以不能用 `Uri.getQueryParameter`（那个只看 `?` 之后、`#` 之前的部分）。
     * 只认 32 位十六进制，避免把别的链接误当成条目。
     */
    fun jellyfinItemIdOf(url: String): String? {
        val query = when {
            url.contains("#/details?") -> url.substringAfter("#/details?")
            url.contains("?") -> url.substringAfter("?")
            else -> return null
        }
        val id = query.split('&')
            .firstOrNull { it.startsWith("id=") }
            ?.removePrefix("id=")
            ?.substringBefore('#')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return id.takeIf { ID_REGEX.matches(it) }
    }
}
