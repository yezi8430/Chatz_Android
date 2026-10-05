package asia.guojuice.yezigotify.ui.dnd

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import asia.guojuice.yezigotify.ui.common.AppTimePickerDialog
import asia.guojuice.yezigotify.ui.common.WeekdaySelector
import asia.guojuice.yezigotify.ui.settings.settingsSwitchColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDndConfigDialog(
    serverId: String,
    appId: Int? = null,
    channelId: Int? = null,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // 作用域分流：有 channelId → 频道级配置（Chatz）；否则 → 应用级配置（Gotify / ntfy）
    val isChannelScope = channelId != null
    val scopeId = channelId ?: (appId ?: 0)

    val initial = remember(serverId, appId, channelId) {
        if (isChannelScope) ChannelDndManager.getConfig(serverId, scopeId)
        else PerAppDndManager.getConfig(serverId, scopeId)
    }

    val globalMerge = remember {
        context.getSharedPreferences("gotify", Context.MODE_PRIVATE)
            .getBoolean("notification_merge", false)
    }

    val globalExemptMin = remember { DoNotDisturbManager.getExemptMin() }
    val globalExemptMax = remember { DoNotDisturbManager.getExemptMax() }

    var silent by remember { mutableStateOf(initial.silent) }
    var customSoundUri by remember { mutableStateOf(initial.customSoundUri) }
    var dndEnabled by remember { mutableStateOf(initial.dndEnabled) }

    var merge by remember { mutableStateOf(initial.merge ?: globalMerge) }
    var mergeModified by remember { mutableStateOf(initial.merge != null) }

    var startHour by remember { mutableIntStateOf(initial.startHour) }
    var startMinute by remember { mutableIntStateOf(initial.startMinute) }
    var endHour by remember { mutableIntStateOf(initial.endHour) }
    var endMinute by remember { mutableIntStateOf(initial.endMinute) }

    var daysOfWeek by remember {
        mutableStateOf(initial.daysOfWeek ?: setOf(1, 2, 3, 4, 5, 6, 7))
    }

    var exemptMin by remember { mutableIntStateOf(initial.exemptMin ?: globalExemptMin) }
    var exemptMax by remember { mutableIntStateOf(initial.exemptMax ?: globalExemptMax) }
    var exemptModified by remember { mutableStateOf(initial.exemptMin != null) }

    var pickerTarget by remember { mutableStateOf<TimeField?>(null) }

    //  时间冲突自动调整后的提示文案；null = 无提示
    var timeConflictWarning by remember { mutableStateOf<String?>(null) }

    val ringtoneName = remember(customSoundUri) {
        getRingtoneName(context, customSoundUri)
    }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            customSoundUri = uri?.toString()
        }
    }

    val crossNight = startHour * 60 + startMinute > endHour * 60 + endMinute

    val isFollowingGlobal = merge == globalMerge
    val isExemptFollowingGlobal =
        exemptMin == globalExemptMin && exemptMax == globalExemptMax

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isChannelScope) "频道独立勿扰配置" else "App 独立勿扰配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // ===== 静默通知开关（弹但不出声） =====
                // ⚠️ 这里刻意**不叫「静音」**：频道长按菜单里那个「不接收通知」才是
                // 完全不弹（硬拦截，服务端同步）；这个只是不响铃/不震动（本机偏好）。
                SwitchRow(
                    title = "静默通知",
                    subtitle = "通知不响铃、不震动",
                    checked = silent,
                    onCheckedChange = { silent = it }
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ===== 通知合并 =====
                SwitchRow(
                    title = "通知合并",
                    subtitle = if (isFollowingGlobal) {
                        "多条消息合并到一条通知（跟随全局）"
                    } else {
                        "多条消息合并到一条通知"
                    },
                    checked = merge,
                    onCheckedChange = {
                        merge = it
                        mergeModified = true
                    }
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ===== 自定义提示音 =====
                Text(
                    "自定义提示音",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ringtoneName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                customSoundUri?.let { Uri.parse(it) }
                            )
                        }
                        ringtoneLauncher.launch(intent)
                    }) { Text("选择") }
                    if (customSoundUri != null) {
                        TextButton(onClick = { customSoundUri = null }) { Text("清除") }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ===== 优先级豁免 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "优先级豁免",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isExemptFollowingGlobal) {
                                "此范围内消息始终不静默（跟随全局）"
                            } else {
                                "此范围内消息始终不静默"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "$exemptMin ~ $exemptMax",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                RangeSlider(
                    value = exemptMin.toFloat()..exemptMax.toFloat(),
                    onValueChange = { range ->
                        val newMin = range.start.roundToInt().coerceIn(0, 10)
                        val newMax = range.endInclusive.roundToInt().coerceIn(0, 10)
                        if (newMin <= newMax) {
                            exemptMin = newMin
                            exemptMax = newMax
                            exemptModified = true
                        }
                    },
                    valueRange = 0f..10f,
                    steps = 9
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ===== 免打扰时段 =====
                SwitchRow(
                    title = "免打扰时段",
                    subtitle = "此时段内通知静默（豁免区间除外）",
                    checked = dndEnabled,
                    onCheckedChange = { dndEnabled = it }
                )

                if (dndEnabled) {
                    Spacer(Modifier.height(12.dp))

                    // ===== 快捷预设 =====
                    Text("快捷预设", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                startHour = 23; startMinute = 0
                                endHour = 7; endMinute = 0
                                daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7)
                                timeConflictWarning = null
                            },
                            label = { Text("睡眠") }
                        )
                        AssistChip(
                            onClick = {
                                startHour = 12; startMinute = 0
                                endHour = 14; endMinute = 0
                                daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7)
                                timeConflictWarning = null
                            },
                            label = { Text("午休") }
                        )
                        AssistChip(
                            onClick = {
                                startHour = 9; startMinute = 0
                                endHour = 18; endMinute = 0
                                daysOfWeek = setOf(1, 2, 3, 4, 5)
                                timeConflictWarning = null
                            },
                            label = { Text("工作") }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // =====  时间冲突自动调整提示 =====
                    timeConflictWarning?.let { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    // ===== 时间显示 =====
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TimeDisplayCard(
                            label = "开始",
                            hour = startHour,
                            minute = startMinute,
                            onClick = { pickerTarget = TimeField.START },
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        TimeDisplayCard(
                            label = "结束",
                            hour = endHour,
                            minute = endMinute,
                            onClick = { pickerTarget = TimeField.END },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (crossNight) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "跨夜时段（结束时间为次日）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // ===== 生效日 =====
                    Text("生效日", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    WeekdaySelector(
                        selected = daysOfWeek,
                        onSelectionChange = { daysOfWeek = it }
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {

                val finalMerge: Boolean? = if (mergeModified) merge else null
                val finalExemptMin: Int? = if (exemptModified) exemptMin else null
                val finalExemptMax: Int? = if (exemptModified) exemptMax else null

                val savedConfig = AppDndConfig(
                    silent = silent,
                    merge = finalMerge,
                    customSoundUri = customSoundUri,
                    dndEnabled = dndEnabled,
                    startHour = startHour,
                    startMinute = startMinute,
                    endHour = endHour,
                    endMinute = endMinute,
                    exemptMin = finalExemptMin,
                    exemptMax = finalExemptMax,
                    daysOfWeek = if (daysOfWeek == setOf(1, 2, 3, 4, 5, 6, 7)) {
                        null
                    } else {
                        daysOfWeek
                    }
                )
                if (isChannelScope) {
                    ChannelDndManager.saveConfig(serverId, scopeId, savedConfig)
                } else {
                    PerAppDndManager.saveConfig(serverId, scopeId, savedConfig)
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val hasCustom = if (isChannelScope) {
                    ChannelDndManager.hasCustomConfig(serverId, scopeId)
                } else {
                    PerAppDndManager.hasCustomConfig(serverId, scopeId)
                }
                if (hasCustom) {
                    TextButton(onClick = {
                        if (isChannelScope) {
                            ChannelDndManager.clearConfig(serverId, scopeId)
                        } else {
                            PerAppDndManager.clearConfig(serverId, scopeId)
                        }
                        onDismiss()
                    }) { Text("恢复默认") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )

    // ===== 时间选择对话框 =====
    pickerTarget?.let { target ->
        val isStart = target == TimeField.START
        AppTimePickerDialog(
            initialHour = if (isStart) startHour else endHour,
            initialMinute = if (isStart) startMinute else endMinute,
            onConfirm = { h, m ->
                if (isStart) {
                    // 用户改 start
                    startHour = h
                    startMinute = m
                    // 冲突检测：调整 end
                    if (h * 60 + m == endHour * 60 + endMinute) {
                        val total = (h * 60 + m + 1) % (24 * 60)
                        endHour = total / 60
                        endMinute = total % 60
                        timeConflictWarning = "结束时间已自动调整为 %02d:%02d".format(endHour, endMinute)
                    } else {
                        timeConflictWarning = null
                    }
                } else {
                    // 用户改 end
                    endHour = h
                    endMinute = m
                    // 冲突检测：调整 start
                    if (h * 60 + m == startHour * 60 + startMinute) {
                        val total = (h * 60 + m + 1) % (24 * 60)
                        startHour = total / 60
                        startMinute = total % 60
                        timeConflictWarning = "开始时间已自动调整为 %02d:%02d".format(startHour, startMinute)
                    } else {
                        timeConflictWarning = null
                    }
                }
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null }
        )
    }
}

private enum class TimeField { START, END }

@Composable
private fun TimeDisplayCard(
    label: String,
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "%02d:%02d".format(hour, minute),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = settingsSwitchColors()
        )
    }
}

private fun getRingtoneName(context: Context, uriString: String?): String {
    if (uriString.isNullOrBlank()) return "默认"
    return try {
        val uri = Uri.parse(uriString)
        val ringtone: Ringtone? = RingtoneManager.getRingtone(context, uri)
        ringtone?.getTitle(context)?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "已选择"
    } catch (_: Exception) {
        "已选择"
    }
}