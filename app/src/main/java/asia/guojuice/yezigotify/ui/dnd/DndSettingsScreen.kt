package asia.guojuice.yezigotify.ui.dnd

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import asia.guojuice.yezigotify.ui.common.AppTimePickerDialog
import asia.guojuice.yezigotify.ui.common.WeekdaySelector
import asia.guojuice.yezigotify.ui.settings.settingsCardColor
import asia.guojuice.yezigotify.ui.settings.settingsCardOnColor
import asia.guojuice.yezigotify.ui.settings.settingsCardOnColorSecondary
import asia.guojuice.yezigotify.utils.BubbleHelper
import kotlin.math.roundToInt
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DndSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gotify", Context.MODE_PRIVATE) }

    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    //  bubbleHelper 仍然保留：
    //    用于"时间冲突自动调整"这个警告提示（不是"设置已生效"提示）
    val bubbleHelper = remember(activity, lifecycleOwner) {
        activity?.let { BubbleHelper(it, lifecycleOwner.lifecycleScope) }
    }

    // 时间段状态
    var startHour by remember { mutableIntStateOf(prefs.getInt("dnd_start_hour", 23)) }
    var startMinute by remember { mutableIntStateOf(prefs.getInt("dnd_start_minute", 0)) }
    var endHour by remember { mutableIntStateOf(prefs.getInt("dnd_end_hour", 7)) }
    var endMinute by remember { mutableIntStateOf(prefs.getInt("dnd_end_minute", 0)) }

    // 生效日状态
    var daysOfWeek by remember {
        mutableStateOf(
            prefs.getString("dnd_days_of_week", "1,2,3,4,5,6,7")
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in 1..7 }
                ?.toSet()
                ?: setOf(1, 2, 3, 4, 5, 6, 7)
        )
    }

    // 豁免区间状态
    var exemptMin by remember { mutableIntStateOf(prefs.getInt("dnd_exempt_min", 8)) }
    var exemptMax by remember { mutableIntStateOf(prefs.getInt("dnd_exempt_max", 10)) }

    LaunchedEffect(exemptMin, exemptMax) {
        prefs.edit()
            .putInt("dnd_exempt_min", exemptMin)
            .putInt("dnd_exempt_max", exemptMax)
            .apply()
        DoNotDisturbManager.reload()
    }

    /**
     *  用户手动调时间（走 picker 或直接改一侧）时的保存入口
     *
     * 会做冲突检测：如果两侧相同，自动调整【用户没动的那一侧】
     * 并弹气泡告知。
     *
     * ⚠️ 只应该被"用户单点某一侧时间"触发，
     *    不能被预设 chips 调用（chips 用 applyPreset，见下）
     */
    fun saveTimeRange(
        newStartHour: Int, newStartMinute: Int,
        newEndHour: Int, newEndMinute: Int,
        adjustWhich: TimeSide
    ) {
        var fStartH = newStartHour
        var fStartM = newStartMinute
        var fEndH = newEndHour
        var fEndM = newEndMinute
        var adjusted: TimeSide? = null

        if (fStartH * 60 + fStartM == fEndH * 60 + fEndM) {
            when (adjustWhich) {
                TimeSide.START -> {
                    val total = (fStartH * 60 + fStartM + 1) % (24 * 60)
                    fEndH = total / 60
                    fEndM = total % 60
                    adjusted = TimeSide.END
                }
                TimeSide.END -> {
                    val total = (fEndH * 60 + fEndM + 1) % (24 * 60)
                    fStartH = total / 60
                    fStartM = total % 60
                    adjusted = TimeSide.START
                }
            }
        }

        startHour = fStartH
        startMinute = fStartM
        endHour = fEndH
        endMinute = fEndM

        prefs.edit()
            .putInt("dnd_start_hour", fStartH)
            .putInt("dnd_start_minute", fStartM)
            .putInt("dnd_end_hour", fEndH)
            .putInt("dnd_end_minute", fEndM)
            .apply()
        DoNotDisturbManager.reload()

        adjusted?.let { side ->
            val tip = when (side) {
                TimeSide.START -> "开始时间已自动调整为 %02d:%02d".format(fStartH, fStartM)
                TimeSide.END -> "结束时间已自动调整为 %02d:%02d".format(fEndH, fEndM)
            }
            bubbleHelper?.show(tip)
        }
    }

    /**
     *  应用预设（chip 专用）
     *
     * 和 saveTimeRange 的区别：
     *   - 一次性写入 4 个时间值 + 生效日，中间不做冲突检测
     *   - 预设本身就保证 start != end，不可能冲突
     *   - 走一个"原子提交"，避免分 3 步触发 3 次回调导致误判
     *
     * 之前的问题：
     *   chip 里调 onStartChange(23, 0) → 那一刻 end 还是旧值（比如 23:00）
     *   → 撞了 → 误弹"结束时间已自动调整"气泡
     *
     * 现在：
     *   chip 里调 onApplyPreset → 一次性把 start/end/days 全设好
     *   → 没有中间状态 → 不会误判
     */
    fun applyPreset(
        newStartHour: Int, newStartMinute: Int,
        newEndHour: Int, newEndMinute: Int,
        days: Set<Int>
    ) {
        startHour = newStartHour
        startMinute = newStartMinute
        endHour = newEndHour
        endMinute = newEndMinute
        daysOfWeek = days

        prefs.edit()
            .putInt("dnd_start_hour", newStartHour)
            .putInt("dnd_start_minute", newStartMinute)
            .putInt("dnd_end_hour", newEndHour)
            .putInt("dnd_end_minute", newEndMinute)
            .apply()
        DoNotDisturbManager.reload()
        DoNotDisturbManager.setDaysOfWeek(days)
    }

    //  方案 A：直接返回，不弹气泡
    //    设置是即时保存的，改完立刻能看到时间卡片更新，
    //    这本身就是"已生效"的反馈，不需要额外的气泡
    BackHandler(enabled = true) {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("勿扰设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DndHintCard()

            TimeRangeCard(
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                daysOfWeek = daysOfWeek,
                onStartChange = { h, m ->
                    saveTimeRange(h, m, endHour, endMinute, TimeSide.START)
                },
                onEndChange = { h, m ->
                    saveTimeRange(startHour, startMinute, h, m, TimeSide.END)
                },
                onDaysChange = { days ->
                    daysOfWeek = days
                    DoNotDisturbManager.setDaysOfWeek(days)
                },
                onApplyPreset = { sh, sm, eh, em, days ->
                    applyPreset(sh, sm, eh, em, days)
                }
            )

            ExemptRangeCard(
                min = exemptMin,
                max = exemptMax,
                onRangeChange = { minVal, maxVal ->
                    exemptMin = minVal
                    exemptMax = maxVal
                }
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

private enum class TimeSide { START, END }

// ========== 说明卡片 ==========

@Composable
private fun DndHintCard() {
    val onCard = settingsCardOnColor()
    val onCardSecondary = settingsCardOnColorSecondary()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "本页为全局设置",
                    style = MaterialTheme.typography.titleSmall,
                    color = onCard
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "如需为单个频道 / App 单独配置（静默通知、免打扰时段等），" +
                            "请打开左侧抽屉，长按频道名或 App 名进入独立配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = onCardSecondary
                )
            }
        }
    }
}

// ========== 时间段卡片 ==========

@Composable
private fun TimeRangeCard(
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    daysOfWeek: Set<Int>,
    onStartChange: (Int, Int) -> Unit,
    onEndChange: (Int, Int) -> Unit,
    onDaysChange: (Set<Int>) -> Unit,
    onApplyPreset: (Int, Int, Int, Int, Set<Int>) -> Unit
) {
    val onCard = settingsCardOnColor()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "勿扰时间段",
                style = MaterialTheme.typography.titleMedium,
                color = onCard
            )
            Spacer(Modifier.height(12.dp))

            // ===== 快捷预设 =====
            //  一次回调，不做拆解，避免中间状态造成误判
            Text("快捷预设", style = MaterialTheme.typography.labelMedium, color = onCard)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onApplyPreset(23, 0, 7, 0, setOf(1, 2, 3, 4, 5, 6, 7)) },
                    label = { Text("睡眠") }
                )
                AssistChip(
                    onClick = { onApplyPreset(12, 0, 14, 0, setOf(1, 2, 3, 4, 5, 6, 7)) },
                    label = { Text("午休") }
                )
                AssistChip(
                    onClick = { onApplyPreset(9, 0, 18, 0, setOf(1, 2, 3, 4, 5)) },
                    label = { Text("工作") }
                )
            }

            Spacer(Modifier.height(14.dp))

            // ===== 时间显示 =====
            TimePickerRow(
                label = "开始时间",
                hour = startHour,
                minute = startMinute,
                onTimeChange = onStartChange
            )
            Spacer(Modifier.height(8.dp))
            TimePickerRow(
                label = "结束时间",
                hour = endHour,
                minute = endMinute,
                onTimeChange = onEndChange
            )

            Spacer(Modifier.height(14.dp))

            // ===== 生效日 =====
            Text("生效日", style = MaterialTheme.typography.labelMedium, color = onCard)
            Spacer(Modifier.height(6.dp))
            WeekdaySelector(
                selected = daysOfWeek,
                onSelectionChange = onDaysChange
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerRow(
    label: String,
    hour: Int,
    minute: Int,
    onTimeChange: (Int, Int) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val onCard = settingsCardOnColor()

    OutlinedCard(
        onClick = { showPicker = true },
        colors = CardDefaults.outlinedCardColors(containerColor = settingsCardColor()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = onCard
            )
            Text(
                "%02d:%02d".format(hour, minute),
                style = MaterialTheme.typography.titleLarge,
                color = onCard
            )
        }
    }

    if (showPicker) {
        AppTimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m ->
                onTimeChange(h, m)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

// ========== 豁免区间卡片 ==========

@Composable
private fun ExemptRangeCard(
    min: Int,
    max: Int,
    onRangeChange: (Int, Int) -> Unit
) {
    val onCard = settingsCardOnColor()
    val onCardSecondary = settingsCardOnColorSecondary()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = settingsCardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "优先级豁免区间",
                style = MaterialTheme.typography.titleMedium,
                color = onCard
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "此范围内的消息在勿扰时段不会被静默",
                style = MaterialTheme.typography.bodySmall,
                color = onCardSecondary
            )
            Spacer(Modifier.height(12.dp))

            Text(
                "$min ~ $max",
                style = MaterialTheme.typography.headlineMedium,
                color = onCard
            )
            Spacer(Modifier.height(8.dp))

            RangeSlider(
                value = min.toFloat()..max.toFloat(),
                onValueChange = { range ->
                    val newMin = range.start.roundToInt().coerceIn(0, 10)
                    val newMax = range.endInclusive.roundToInt().coerceIn(0, 10)
                    if (newMin <= newMax) {
                        onRangeChange(newMin, newMax)
                    }
                },
                valueRange = 0f..10f,
                steps = 9
            )
        }
    }
}