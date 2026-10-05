package asia.guojuice.yezigotify

import androidx.compose.runtime.mutableStateOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用图标 / 应用列表缓存
 *
 * 多服务器改造：按 **serverId 分桶**（一台服务器一个 [Bucket]）。
 *   - 一台服务器重载不应把另一台进行中的请求判为过期 → generation 也是 **per-bucket**
 *   - [getAppList] 返回的 id 仍是**服务端原始 appid**（不合成 filter id）：
 *     通知图标查找（GotifyService）用的就是原始 appid；合成 filter id 只在抽屉侧拼
 */
object AppIconCache {

    /**
     * 一台服务器的缓存桶
     *
     * 后台线程写、UI 线程读，字段全部 @Volatile / 并发容器。
     */
    private class Bucket {
        val iconMap = ConcurrentHashMap<Int, String>()

        @Volatile
        var appList: List<GotifyApp> = emptyList()

        @Volatile
        var loaded: Boolean = false

        /** 已加载完成的服务器指纹（serverUrl|clientToken） */
        @Volatile
        var loadedKey: String? = null

        /** 正在加载中的服务器指纹（合并同一服务器的并发请求） */
        @Volatile
        var loadingKey: String? = null

        /** 该桶的请求代际（快速切换时丢弃过期响应，不影响其它桶） */
        val generation = AtomicInteger(0)

        /**
         * 该服务器的基址（已补协议），用来把消息 extras 里的相对图标路径拼成绝对 URL。
         * 消息自带的应用快照（extras.app.image）是相对路径，得靠它补全。
         */
        @Volatile
        var baseUrl: String = ""
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    private var onLoaded: (() -> Unit)? = null

    // Compose State：任一桶加载完成后 +1，读它的 Composable 会重组
    val loadVersion = mutableStateOf(0)

    private fun bucketOf(serverId: String): Bucket =
        buckets.getOrPut(serverId) { Bucket() }

    fun getIconUrl(serverId: String, appId: Int): String? =
        buckets[serverId]?.iconMap?.get(appId)

    /** 已加载完成的原始应用列表（未加载完成返回 null，与改造前语义一致） */
    fun getAppList(serverId: String): List<GotifyApp>? =
        buckets[serverId]?.let { if (it.loaded) it.appList else null }

    /**
     * 把服务端返回的**相对**图片路径拼成绝对 URL
     *
     * 消息自带的 extras.app.image（应用快照）是 `/icons/xxx.png` 这种相对路径 ——
     * 服务端不知道对外地址，只能客户端拼。baseUrl 取自该服务器的桶（load 时写入）。
     * 桶还没建就返回 null，调用方自行回退（会退到按 appid 查的 [getIconUrl]）。
     */
    fun resolveUrl(serverId: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = buckets[serverId]?.baseUrl
        if (base.isNullOrBlank()) return null
        return base.trimEnd('/') + "/" + path.trimStart('/')
    }

    fun setOnLoadedCallback(callback: () -> Unit) { onLoaded = callback }
    fun clearOnLoadedCallback() { onLoaded = null }

    /**
     * 加载一台服务器的应用图标列表
     *
     *  同一服务器重复调用会命中已加载 / 正在加载检查
     *  快速切换（同一桶内 url/token 变化）用 per-bucket generation 丢弃过期响应
     */
    fun loadAppIcons(serverId: String, serverUrl: String, clientToken: String) {
        load(serverId, serverUrl, clientToken, force = false)
    }

    /**
     * 强制重新拉取应用列表（打开抽屉时用）
     *
     * 与 [loadAppIcons] 的两点区别：
     *   1. 跳过「已加载过同地址就直接返回」的短路 —— 服务端新建/删除应用后
     *      客户端原本要杀进程重开才能看到（进程内只拉一次）
     *   2. **不清空旧数据** —— 旧列表保持可见，等新响应到了再整体替换。
     *      否则抽屉一打开「应用」分组会先空一下再填上
     */
    fun reloadAppIcons(serverId: String, serverUrl: String, clientToken: String) {
        load(serverId, serverUrl, clientToken, force = true)
    }

    private fun load(
        serverId: String,
        serverUrl: String,
        clientToken: String,
        force: Boolean
    ) {
        if (serverUrl.isBlank() || clientToken.isBlank()) {
            AppLog.d("AppIconCache", "服务器未配置，跳过加载图标: $serverId")
            // 强制重拉且配置为空时也要回调，否则调用方等的刷新永远不来
            postOnLoaded()
            return
        }

        val key = "$serverUrl|$clientToken"
        val bucket = bucketOf(serverId)

        // 相同服务器的请求正在飞行中 → 合并，不重复发（force 也一样，避免连点抽屉狂发请求）
        if (bucket.loadingKey == key) {
            AppLog.d("AppIconCache", "相同服务器的加载已在进行中，跳过重复请求: $serverId")
            return
        }

        // 已加载相同服务器且非强制 → 直接回调，不重复请求
        if (!force && bucket.loaded && bucket.loadedKey == key) {
            onLoaded?.invoke()
            return
        }

        if (!force) {
            // 服务器变了（同 serverId 换地址/凭据）：立即清空该桶旧数据，避免串台
            bucket.iconMap.clear()
            bucket.appList = emptyList()
            bucket.loaded = false
            bucket.loadedKey = null
        }
        bucket.loadingKey = key

        val myGeneration = bucket.generation.incrementAndGet()

        Thread {
            var conn: HttpURLConnection? = null
            try {
                var baseUrl = serverUrl.trimEnd('/')
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    baseUrl = "https://$baseUrl"
                }
                // 记进桶：消息 extras 里的应用图标是相对路径，靠它拼绝对 URL
                bucket.baseUrl = baseUrl
                val url = URL("$baseUrl/application")

                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-Gotify-Key", clientToken)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }

                    // 先在局部变量构建，避免并发读到半写状态
                    val newIconMap = mutableMapOf<Int, String>()
                    val newAppList = mutableListOf<GotifyApp>()

                    val jsonArray = JsonParser.parseString(body).asJsonArray
                    jsonArray.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.intOrNull("id")
                        val name = obj.stringOrNull("name") ?: ""
                        val image = obj.stringOrNull("image")
                        val description = obj.stringOrNull("description")
                        // Chatz 才有：该应用的默认频道，发消息时用来推导 appid
                        val channelId = obj.intOrNull("channelId")
                        if (id != null) {
                            if (!image.isNullOrEmpty()) {
                                newIconMap[id] = resolveIconUrl(baseUrl, image)
                            }
                            newAppList.add(GotifyApp(id, name, description, channelId))
                        }
                    }

                    // 代际检查：期间该桶已有新请求发出 → 本次响应已过期，丢弃
                    if (bucket.generation.get() != myGeneration) {
                        AppLog.d(
                            "AppIconCache",
                            "服务器已切换，丢弃过期响应 (server=$serverId, gen=$myGeneration)"
                        )
                        return@Thread
                    }

                    // 一次性替换该桶
                    bucket.iconMap.clear()
                    bucket.iconMap.putAll(newIconMap)
                    bucket.appList = newAppList.toList()
                    bucket.loaded = true
                    bucket.loadedKey = key

                    // 先通知 UI（避免 UI 等图标 preload 完成才显示列表）
                    postOnLoaded()

                    // 预加载改为后台异步、非阻塞
                    val ctx = GotifyApplication.instance
                    val iconSnapshot = newIconMap.values.toList()
                    val preloadGeneration = myGeneration
                    Thread {
                        iconSnapshot.forEach { iconUrl ->
                            // 预加载过程中也检查代际，切服务器立即停下
                            if (bucket.generation.get() != preloadGeneration) return@Thread
                            try {
                                com.bumptech.glide.Glide.with(ctx)
                                    .load(iconUrl)
                                    .diskCacheStrategy(
                                        com.bumptech.glide.load.engine.DiskCacheStrategy.ALL
                                    )
                                    .preload()
                            } catch (e: Exception) {
                                AppLog.e("AppIconCache", "预加载图标失败: $iconUrl", e)
                            }
                        }
                    }.start()
                } else {
                    postOnLoaded()
                }
            } catch (e: Exception) {
                AppLog.e("AppIconCache", "加载应用图标异常", e)
                postOnLoaded()
            } finally {
                // 只有自己还是"最新的 loading"才清空，避免新请求把旧的 finally 误清空
                if (bucket.loadingKey == key) {
                    bucket.loadingKey = null
                }
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    /**
     * 安全读取可空字符串字段
     *
     * ⚠️ 不能写成 `obj.get("image")?.asString`：
     *   Gson 把 JSON 里的 null 解析成 JsonNull **对象**（不是 Java 的 null），
     *   所以 `?.` 不会短路，asString 会抛 UnsupportedOperationException: JsonNull。
     *   服务端 /application 返回的 image / description 都可能是 null，必现。
     */
    private fun JsonObject.stringOrNull(key: String): String? {
        val el = get(key) ?: return null
        return if (el.isJsonNull) null else el.asString
    }

    /** 安全读取可空整数字段（同上，防 JsonNull） */
    private fun JsonObject.intOrNull(key: String): Int? {
        val el = get(key) ?: return null
        return if (el.isJsonNull) null else el.asInt
    }

    /**
     * 服务端的 image 字段 → 可直接加载的绝对 URL
     *
     * ⚠️ 服务端存的是**带前导斜杠**的路径（`index.js` 上传图标后写入
     * `image = '/icons/<id>-<ts>.png'`）。若直接拼成 `"$baseUrl/$image"`，
     * 会得到 `https://host//icons/xxx.png` 这种双斜杠地址 —— 它匹配不上
     * `app.use('/icons', express.static(...))`，会掉到后面的全局鉴权里
     * 返回 **401**，表现为应用图标变成空白。
     *
     * 频道图标没这个问题，因为 `MessageViewModel.resolveImageUrl()` 已经处理了
     * 前导斜杠（存库前就转成绝对地址），只有应用图标走这里。
     */
    private fun resolveIconUrl(baseUrl: String, image: String): String {
        if (image.startsWith("http://") || image.startsWith("https://")) return image
        return if (image.startsWith("/")) "$baseUrl$image" else "$baseUrl/$image"
    }

    private fun postOnLoaded() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            loadVersion.value++
            onLoaded?.invoke()
        }
    }
}