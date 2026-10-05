package asia.guojuice.yezigotify.ui.dnd

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object DoNotDisturbManager {

    private const val PREFS_NAME = "gotify"
    private const val KEY_DAYS = "dnd_days_of_week"

    /**
     * 默认生效日：全周
     * 存储格式：逗号分隔的整数，如 "1,2,3,4,5,6,7"
     * 空字符串 "" 表示"用户主动清空所有日" = 永不生效
     */
    private const val DEFAULT_DAYS = "1,2,3,4,5,6,7"

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    private lateinit var prefs: SharedPreferences

    @Volatile private var enabled = false
    @Volatile private var startHour = 23
    @Volatile private var startMinute = 0
    @Volatile private var endHour = 7
    @Volatile private var endMinute = 0
    @Volatile private var exemptMin = 8
    @Volatile private var exemptMax = 10

    /** 生效日：1=周一 ... 7=周日 */
    @Volatile private var daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        reload()
    }

    fun reload() {
        enabled = prefs.getBoolean("dnd_enabled", false)
        startHour = prefs.getInt("dnd_start_hour", 23)
        startMinute = prefs.getInt("dnd_start_minute", 0)
        endHour = prefs.getInt("dnd_end_hour", 7)
        endMinute = prefs.getInt("dnd_end_minute", 0)
        exemptMin = prefs.getInt("dnd_exempt_min", 8)
        exemptMax = prefs.getInt("dnd_exempt_max", 10)
        daysOfWeek = parseDays(prefs.getString(KEY_DAYS, DEFAULT_DAYS))
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("dnd_enabled", value).apply()
        notifyListeners()
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it(enabled) }
    }

    //  只有两种行为：正常 / 静默
    enum class Behavior { ALLOW, SILENT }

    fun getNotificationBehavior(priority: Int): Behavior {
        // 勿扰未开启 → 正常
        if (!enabled) return Behavior.ALLOW

        // 不在时间段内 → 正常
        if (!isInTimeRange()) return Behavior.ALLOW

        // 优先级在豁免范围内 → 正常
        if (priority in exemptMin..exemptMax) return Behavior.ALLOW

        // 其余情况 → 静默（显示通知但不发声不震动）
        return Behavior.SILENT
    }

    /**
     * 判断"现在"是否落在勿扰时段内
     *
     *  加入了"生效日"的判断，逻辑分层：
     *   1. 先判断"现在是哪一天"，看是否在 daysOfWeek 里
     *   2. 再判断"现在几点"，看是否落在 [start, end] 内
     *
     * ⚠️ 跨夜场景的坑：
     *   假设时段是 "23:00 ~ 07:00"，生效日选"周五"
     *   - 周五 23:30 → 静默 ✓（今天=周五，在集合里）
     *   - 周六 02:00 → 应该静默！（因为时段是从"周五 23:00"延续过来的）
     *     → 所以跨夜后段（凌晨）要判断的是"昨天"的周几
     */
    private fun isInTimeRange(): Boolean {
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinuteOfDay = startHour * 60 + startMinute
        val endMinuteOfDay = endHour * 60 + endMinute

        val today = todayAsOurEncoding(now)
        val yesterday = if (today == 1) 7 else today - 1

        return when {
            // ===== 非跨夜：09:00 ~ 18:00 =====
            startMinuteOfDay < endMinuteOfDay -> {
                currentMinute in startMinuteOfDay..endMinuteOfDay &&
                        today in daysOfWeek
            }

            // ===== 跨夜：23:00 ~ 07:00 =====
            startMinuteOfDay > endMinuteOfDay -> {
                when {
                    // 今晚（跨夜前段）：判断今天
                    currentMinute >= startMinuteOfDay -> today in daysOfWeek
                    // 次日凌晨（跨夜后段）：判断昨天
                    currentMinute <= endMinuteOfDay -> yesterday in daysOfWeek
                    else -> false
                }
            }

            // ===== start == end：视作全天生效 =====
            else -> today in daysOfWeek
        }
    }

    /**
     * Calendar.DAY_OF_WEEK 转成本组件编码
     * Calendar: 1=周日 2=周一 ... 7=周六
     * 本组件:  1=周一 ... 7=周日
     */
    private fun todayAsOurEncoding(cal: Calendar): Int {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    // 供设置页读取
    fun getStartHour(): Int = startHour
    fun getStartMinute(): Int = startMinute
    fun getEndHour(): Int = endHour
    fun getEndMinute(): Int = endMinute
    fun getExemptMin(): Int = exemptMin
    fun getExemptMax(): Int = exemptMax

    /** 读取生效日 */
    fun getDaysOfWeek(): Set<Int> = daysOfWeek

    /**
     * 保存生效日
     * 与 setEnabled 不同：这里【不】通知 listeners，
     * 因为 listeners 只订阅"勿扰开关状态"的变化，日变化不影响开关本身。
     */
    fun setDaysOfWeek(days: Set<Int>) {
        daysOfWeek = days
        prefs.edit().putString(KEY_DAYS, serializeDays(days)).apply()
    }

    /** 解析 "1,2,3" 格式字符串 → Set<Int> */
    private fun parseDays(raw: String?): Set<Int> {
        if (raw == null) return setOf(1, 2, 3, 4, 5, 6, 7)  // 键不存在 → 默认全周
        if (raw.isBlank()) return emptySet()                  // 键存在但为空 → 用户清空
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()
    }

    /** Set<Int> → "1,2,3" */
    private fun serializeDays(days: Set<Int>): String {
        return days.sorted().joinToString(",")
    }
}