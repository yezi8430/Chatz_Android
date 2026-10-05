package asia.guojuice.yezigotify.ui.dnd

import android.content.Context
import android.content.SharedPreferences
import asia.guojuice.yezigotify.config.ServerConfigStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个 App 的勿扰配置
 *
 * ⚠️ daysOfWeek 是 nullable，这是 Gson 兼容性设计：
 *   - 声明为 Set<Int>? 而不是 Set<Int>
 *   - 老版本 JSON 里没有这个字段，Gson 反射创建对象时不会调用构造函数，
 *     Kotlin non-null 类型会被填 null（绕过 Kotlin 空检查）
 *   - 用 null 表示"老数据"或"未自定义"，行为等同"全周"
 *   - 空集 emptySet() 表示"用户主动清空所有日"，行为是"永不生效"
 *   - 这两者语义不同，必须区分
 */
data class AppDndConfig(
    val silent: Boolean = false,
    val merge: Boolean? = null,
    val customSoundUri: String? = null,
    val dndEnabled: Boolean = false,
    val startHour: Int = 23,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 0,
    val exemptMin: Int? = null,
    val exemptMax: Int? = null,
    val daysOfWeek: Set<Int>? = null
) {
    /**
     * 该 App 的 DND 时段是否包含"现在"
     *
     *  逻辑与 DoNotDisturbManager.isInTimeRange() 完全一致：
     *   1. 先判断"今天是周几"，是否在 daysOfWeek 里
     *   2. 再判断"几点"，是否落在 [start, end] 里
     *   3. 跨夜时段的凌晨要判断"昨天"的周几
     *
     * null 兜底：daysOfWeek == null 视为全周（老数据兼容）
     */
    fun isNowInTimeRange(): Boolean {
        if (!dndEnabled) return false

        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute

        val days = daysOfWeek ?: setOf(1, 2, 3, 4, 5, 6, 7)

        val today = todayAsOurEncoding(now)
        val yesterday = if (today == 1) 7 else today - 1

        return when {
            start < end -> {
                // 非跨夜
                cur in start..end && today in days
            }
            start > end -> {
                // 跨夜
                when {
                    cur >= start -> today in days       // 今晚
                    cur <= end -> yesterday in days     // 次日凌晨
                    else -> false
                }
            }
            else -> {
                // start == end，视作全天
                today in days
            }
        }
    }

    /** Calendar.DAY_OF_WEEK → 本组件编码（1=周一 ... 7=周日） */
    private fun todayAsOurEncoding(cal: Calendar): Int {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    fun resolvedExemptMin(globalMin: Int): Int = exemptMin ?: globalMin

    fun resolvedExemptMax(globalMax: Int): Int = exemptMax ?: globalMax

    fun isPriorityExempt(priority: Int, globalMin: Int, globalMax: Int): Boolean {
        val min = resolvedExemptMin(globalMin)
        val max = resolvedExemptMax(globalMax)
        return priority in min..max
    }

    fun shouldBeSilent(): Boolean = silent || isNowInTimeRange()
}

/**
 * 每个 App 独立勿扰配置管理
 *
 * 多服务器改造：key 从 `appId`(Int) 改为 `"$serverId|$appId"`(String)。
 * 旧数据（key 是纯数字）在 [load] 时一次性补上 `legacy-gotify|` 前缀，
 * 保证升级后每 App 免打扰 / 铃声配置不丢。
 */
object PerAppDndManager {

    private const val PREFS_NAME = "gotify"
    private const val KEY_CONFIGS = "per_app_dnd_configs"

    /** 旧数据的归属服务器（= ServerConfigStore.LEGACY_SERVER_ID） */
    private val LEGACY_SERVER_ID = ServerConfigStore.LEGACY_SERVER_ID

    private val gson = Gson()

    private val cache = ConcurrentHashMap<String, AppDndConfig>()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 组合 key：一台服务器的一个 App */
    private fun keyOf(serverId: String, appId: Int): String = "$serverId|$appId"

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
                map.forEach { (k, v) ->
                    // 旧格式（纯数字 key）→ 补 legacy-gotify 前缀；新格式（含 '|'）原样使用
                    val migrated = if (k.contains('|')) {
                        k
                    } else {
                        k.toIntOrNull()?.let { keyOf(LEGACY_SERVER_ID, it) }
                    }
                    if (migrated != null) newMap[migrated] = v
                }
            } catch (_: Exception) {
            }
        }

        cache.clear()
        cache.putAll(newMap)
    }

    fun getConfig(serverId: String, appId: Int): AppDndConfig =
        cache[keyOf(serverId, appId)] ?: AppDndConfig()

    fun saveConfig(serverId: String, appId: Int, config: AppDndConfig) {
        val key = keyOf(serverId, appId)
        cache[key] = config
        persist()
    }

    fun clearConfig(serverId: String, appId: Int) {
        val key = keyOf(serverId, appId)
        if (cache.remove(key) != null) {
            persist()
        }
    }

    fun hasCustomConfig(serverId: String, appId: Int): Boolean =
        cache.containsKey(keyOf(serverId, appId))

    private fun persist() {
        val p = prefs ?: return
        try {
            val snapshot = HashMap(cache)
            p.edit().putString(KEY_CONFIGS, gson.toJson(snapshot)).apply()
        } catch (_: Exception) {
        }
    }
}