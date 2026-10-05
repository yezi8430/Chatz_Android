package asia.guojuice.yezigotify.ui.dnd

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

/**
 * 每个频道独立的通知（勿扰 / 铃声 / 合并）配置管理
 *
 * 为什么按**频道**而不是按**应用**：
 *   Chatz 的消息是按「频道」组织的（一条消息属于一个频道，见 GotifyMessage.channelId），
 *   同一个应用可以把消息发到多个频道。用户希望的是「某个频道单独配通知」，
 *   而不是「某个应用整体配通知」——例如把"运维告警"频道设成免打扰，
 *   但同一个 bot 应用在"值班"频道的消息仍要响铃。
 *
 *   所以 Chatz 服务器走这套频道级配置；Gotify / ntfy 没有频道概念，
 *   继续走 [PerAppDndManager] 的应用级配置。两套配置分开存储、互不影响：
 *     - 应用级：prefs key = "per_app_dnd_configs"
 *     - 频道级：prefs key = "per_channel_dnd_configs"
 *   因此频道配置是全新的命名空间，**不需要**对老数据做迁移。
 */
object ChannelDndManager {

    private const val PREFS_NAME = "gotify"
    private const val KEY_CONFIGS = "per_channel_dnd_configs"

    private val gson = Gson()

    private val cache = ConcurrentHashMap<String, AppDndConfig>()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 组合 key：一台服务器的一个频道。channelId 是本地命名空间化的频道 id（全局唯一） */
    private fun keyOf(serverId: String, channelId: Int): String = "$serverId|$channelId"

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    @Synchronized
    private fun load() {
        val p = prefs ?: return
        val json = p.getString(KEY_CONFIGS, null)

        val newMap = mutableMapOf<String, AppDndConfig>()
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Map<String, AppDndConfig>>() {}.type
                val map: Map<String, AppDndConfig> = gson.fromJson(json, type) ?: emptyMap()
                newMap.putAll(map)
            } catch (_: Exception) {
            }
        }

        cache.clear()
        cache.putAll(newMap)
    }

    fun getConfig(serverId: String, channelId: Int): AppDndConfig =
        cache[keyOf(serverId, channelId)] ?: AppDndConfig()

    fun saveConfig(serverId: String, channelId: Int, config: AppDndConfig) {
        cache[keyOf(serverId, channelId)] = config
        persist()
    }

    fun clearConfig(serverId: String, channelId: Int) {
        if (cache.remove(keyOf(serverId, channelId)) != null) {
            persist()
        }
    }

    fun hasCustomConfig(serverId: String, channelId: Int): Boolean =
        cache.containsKey(keyOf(serverId, channelId))

    private fun persist() {
        val p = prefs ?: return
        try {
            val snapshot = HashMap(cache)
            p.edit().putString(KEY_CONFIGS, gson.toJson(snapshot)).apply()
        } catch (_: Exception) {
        }
    }
}