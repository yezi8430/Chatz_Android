package asia.guojuice.yezigotify.capabilities

import android.content.Context
import com.google.gson.JsonParser
import asia.guojuice.yezigotify.config.ServerConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 服务端能力持有者
 *
 * 多服务器改造：能力**按 serverId 分桶**（[byServer]），持久化 key 变
 * `server_caps_<serverId>_type` 等；首次运行会把旧的单源 key 读进
 * [ServerConfigStore.LEGACY_SERVER_ID] 桶（避免升级瞬间抽屉标签从 Chatz 退回 Gotify）。
 *
 * 生命周期：
 *   1. GotifyApplication.onCreate → loadFromCache()  【同步，从 SharedPreferences 读】
 *   2. 切换/新增服务器 → refresh(context, serverId, url, token)  【异步，探测真实服务端】
 *   3. 任意位置 → getCurrent(serverId) / isChatz(serverId)      【无锁读】
 *
 * 兼容旧签名：[getCurrent] / [getServerType] / [refresh]（3 参）
 * 一律按「slot 0 的协议服务器」（legacy 桶）语义，供尚未改造的读取点使用。
 *
 * 并发安全：
 *   - byServer 用 ConcurrentHashMap，读取无锁
 *   - refresh() 每个 serverId 一把 Mutex，防止并发探测同一个服务端
 *   - 探测失败时不清空该桶（保留旧值，避免断网导致能力丢失）
 */
object ServerCapabilitiesHolder {

    private const val TAG = "ServerCapabilities"
    private const val PREFS_NAME = "gotify"

    /** 新格式持久化 key 前缀：server_caps_<serverId>_type / _https_port / _detected_at */
    private const val PREFIX = "server_caps_"
    private const val SUFFIX_TYPE = "_type"
    private const val SUFFIX_HTTPS_PORT = "_https_port"
    private const val SUFFIX_DETECTED_AT = "_detected_at"

    // ===== 旧版单源 key（升级时读进 legacy 桶；P7 删除） =====
    private const val KEY_TYPE = "server_caps_type"
    private const val KEY_HTTPS_PORT = "server_caps_https_port"
    private const val KEY_DETECTED_AT = "server_caps_detected_at"

    /** 旧配置迁移出的协议服务器 id（slot 0） */
    private val LEGACY_ID = ServerConfigStore.LEGACY_SERVER_ID

    private val byServer = ConcurrentHashMap<String, ServerCapabilities>()

    private val probeMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * 探测专用的 OkHttpClient
     *   - 短超时（探测失败要快，不能卡 UI）
     *   - 不跟随重定向（/config 若被重定向，说明不是 Chatz）
     */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    // ==================== 读 ====================

    /** 指定服务器的能力（无记录时返回 UNKNOWN） */
    fun getCurrent(serverId: String): ServerCapabilities =
        byServer[serverId] ?: ServerCapabilities()

    /**
     * 给定的这些服务器里，是否有任意一台被判定为 Chatz
     *
     * 抽屉里「未读」「发现频道」「全部已读」这些 Chatz 专有入口的显隐依据。
     *
     * ⚠️ 参数刻意要传入 serverId 列表，而不是直接扫全部桶：停用一张 Chatz 卡之后，
     * 它的能力探测结果**仍留在缓存里**（那是按 serverId 持久化的），
     * 不按"已启用"过滤的话，这些入口会在卡片停用后继续显示。
     */
    fun anyChatz(serverIds: Collection<String>): Boolean =
        serverIds.any { byServer[it]?.isChatz == true }

    // ---- 旧签名（slot 0 的协议服务器语义） ----

    fun getCurrent(): ServerCapabilities = getCurrent(LEGACY_ID)

    fun getServerType(): ServerType = getCurrent().serverType

    /** 指定服务器是否被判定为 Chatz */
    fun isChatz(serverId: String): Boolean = getCurrent(serverId).isChatz

    /**
     * 从 SharedPreferences 同步加载（App 启动时调用，无网络请求）
     *
     * 需要排在 [ServerConfigStore.migrateLegacyIfNeeded] 之后（本方法会读 legacy id）。
     */
    fun loadFromCache(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val out = LinkedHashMap<String, ServerCapabilities>()

        // 1) 新格式：server_caps_<serverId>_type
        for ((key, value) in prefs.all) {
            if (key == KEY_TYPE) continue
            if (!key.startsWith(PREFIX) || !key.endsWith(SUFFIX_TYPE)) continue
            val sid = key.removeSuffix(SUFFIX_TYPE).removePrefix(PREFIX)
            if (sid.isEmpty()) continue
            val typeStr = value as? String ?: continue
            out[sid] = buildFromType(
                typeStr,
                prefs.getInt("$PREFIX$sid$SUFFIX_HTTPS_PORT", 20443),
                prefs.getLong("$PREFIX$sid$SUFFIX_DETECTED_AT", 0L)
            )
        }

        // 2) 旧单源 key → legacy 桶（仅当该桶还没有新格式数据时）
        if (LEGACY_ID !in out) {
            val typeStr = prefs.getString(KEY_TYPE, null)
            if (typeStr != null) {
                out[LEGACY_ID] = buildFromType(
                    typeStr,
                    prefs.getInt(KEY_HTTPS_PORT, 20443),
                    prefs.getLong(KEY_DETECTED_AT, 0L)
                )
            }
        }

        byServer.clear()
        byServer.putAll(out)
    }

    // ==================== 写 ====================

    /**
     * 异步探测 + 持久化（指定服务器）
     */
    suspend fun refresh(context: Context, serverId: String, serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            byServer[serverId] = ServerCapabilities()
            return
        }

        mutexFor(serverId).withLock {
            val detected = probe(serverUrl, token)
            if (detected != null) {
                byServer[serverId] = detected
                persist(context, serverId, detected)
            } else {
                // 探测失败：保留该桶（避免断网清空），
                // 但如果它是 UNKNOWN，至少给个 Gotify 兜底
                if (!getCurrent(serverId).isKnown) {
                    byServer[serverId] = ServerCapabilities.forGotify()
                }
            }
        }
    }

    // ==================== 内部 ====================

    private fun mutexFor(serverId: String): Mutex =
        probeMutexes.getOrPut(serverId) { Mutex() }

    private fun buildFromType(typeStr: String, httpsPort: Int, detectedAt: Long): ServerCapabilities =
        when (typeStr) {
            "CHATZ" -> ServerCapabilities.forChatz(httpsPort).copy(detectedAt = detectedAt)
            "GOTIFY" -> ServerCapabilities.forGotify().copy(detectedAt = detectedAt)
            else -> ServerCapabilities()
        }

    private fun persist(context: Context, serverId: String, caps: ServerCapabilities) {
        val typeStr = when (caps.serverType) {
            ServerType.CHATZ -> "CHATZ"
            ServerType.GOTIFY -> "GOTIFY"
            ServerType.UNKNOWN -> null
        } ?: return

        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("$PREFIX$serverId$SUFFIX_TYPE", typeStr)
            .putInt("$PREFIX$serverId$SUFFIX_HTTPS_PORT", caps.httpsPort)
            .putLong("$PREFIX$serverId$SUFFIX_DETECTED_AT", caps.detectedAt)
            .apply()
    }

    /**
     * 探测实现
     *
     * 按优先级依次尝试，任一命中即返回
     *   /config  →  {certsUiEnabled, httpsPort}         →  CHATZ
     *   /health  →  {ok, online, https, ts}             →  CHATZ
     *   /channel →  200 且是 JSON 数组                   →  CHATZ
     *   拿到响应但没有 Chatz 特征 →  GOTIFY（协议兼容兜底）
     *   三次请求都没拿到任何响应（网络不可达）→  null（调用方保留旧值，不误报成 Gotify）
     */
    private suspend fun probe(serverUrl: String, token: String): ServerCapabilities? =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(serverUrl)
            if (base.isEmpty()) return@withContext null

            // 是否至少拿到过一次 HTTP 响应（用来区分"服务器不是 Chatz"和"服务器连不上"）
            var gotAnyResponse = false

            // 1. /config
            tryGet("$base/config", token)?.let { resp ->
                gotAnyResponse = true
                resp.body?.let { body ->
                    val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    if (json != null && (json.has("certsUiEnabled") || json.has("httpsPort"))) {
                        val httpsPort = json.get("httpsPort")?.asInt ?: 20443
                        logD("探测命中 /config → CHATZ (httpsPort=$httpsPort)")
                        return@withContext ServerCapabilities.forChatz(httpsPort)
                    }
                }
            }

            // 2. /health
            tryGet("$base/health", null)?.let { resp ->
                gotAnyResponse = true
                resp.body?.let { body ->
                    val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    if (json != null && json.has("online") && json.has("https")) {
                        logD("探测命中 /health → CHATZ")
                        return@withContext ServerCapabilities.forChatz()
                    }
                }
            }

            // 3. /channel（Chatz 专有）
            tryGet("$base/channel", token)?.let { resp ->
                gotAnyResponse = true
                if (resp.body?.trim()?.startsWith("[") == true) {
                    logD("探测命中 /channel → CHATZ")
                    return@withContext ServerCapabilities.forChatz()
                }
            }

            if (!gotAnyResponse) {
                logD("探测失败：三次请求都没拿到响应，保留上次结果")
                return@withContext null
            }

            // 4. 兜底：Gotify
            logD("探测未命中 Chatz 特征 → GOTIFY")
            ServerCapabilities.forGotify()
        }

    /**
     * 单次探测请求的结果包装
     *
     * @param body 2xx 时的响应体；非 2xx 时为 null
     */
    private class ProbeResponse(val body: String?)

    /**
     * @return null 表示请求没拿到任何 HTTP 响应（网络不可达、超时）；
     *         非 null 表示拿到了响应，此时 [ProbeResponse.body] 为 null 说明状态码非 2xx
     */
    private fun tryGet(url: String, token: String?): ProbeResponse? {
        return try {
            val builder = Request.Builder().url(url).get()
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
                // 兼容旧 Gotify：带上 X-Gotify-Key，服务端两个 header 都能识别
                builder.header("X-Gotify-Key", token)
            }
            probeClient.newCall(builder.build()).execute().use { resp ->
                ProbeResponse(if (resp.isSuccessful) resp.body?.string() else null)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeBase(url: String): String {
        var s = url.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        return s
    }

    private fun logD(msg: String) {
        asia.guojuice.yezigotify.AppLog.d(TAG, msg)
    }
}