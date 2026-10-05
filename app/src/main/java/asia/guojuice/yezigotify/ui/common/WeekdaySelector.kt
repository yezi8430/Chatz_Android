package asia.guojuice.yezigotify.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 周几选择器
 *
 * 用途：
 *   - 全局勿扰设置（DndSettingsScreen）
 *   - 单个 App 的勿扰配置（AppDndConfigDialog）
 *
 * 约定：
 *   - 天的编号 1~7，对应 周一~周日
 *     （与 java.util.Calendar.DAY_OF_WEEK 不同，Calendar 是 1=周日；
 *      这里选 1=周一 是为了和 UI 上"一 二 三 四 五 六 日"的阅读顺序一致，
 *      转换由调用方负责）
 *   - 允许全不选（语义 = 永不生效），组件本身不做限制
 *   - 允许全选（语义 = 每天都生效）
 *
 * 视觉：
 *   - 七个圆形按钮等宽排列，间距 4dp
 *   - 整体最大宽度 320dp，防止在平板/横屏上 chip 被拉得过大
 *   - 选中：主题色背景 + 主题色上文字 + SemiBold
 *   - 未选中：surfaceVariant 背景 + onSurfaceVariant 文字 + Normal
 *   - 禁用：整体变灰（alpha 0.4）
 *   - 下方一行总结文案，自动识别常见组合（每天 / 工作日 / 周末 / 自定义），
 *     空集时用 error 色提示"不会生效"
 */
@Composable
fun WeekdaySelector(
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp),   //  限制最大宽度，避免大屏 chip 过大
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val day = index + 1   // 1..7
                val isSelected = day in selected

                WeekdayChip(
                    label = label,
                    selected = isSelected,
                    enabled = enabled,
                    onClick = {
                        val newSet = if (isSelected) selected - day else selected + day
                        onSelectionChange(newSet)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        //  总结文案
        //
        // 放在 chip 行下方而不是上方，原因：
        //   - 用户点 chip 时，视线自然落在下方（阅读顺序 + 手指位置）
        //   - 不需要额外的标题文字，一眼就能看到"当前选了哪几天"
        Spacer(Modifier.height(6.dp))
        Text(
            text = summarizeSelection(selected),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected.isEmpty()) {
                // 空集 = 永不生效，是一个"用户可能漏操作"的错误状态，用 error 色提醒
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun WeekdayChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val themeColor = MaterialTheme.colorScheme.primaryContainer
    val onThemeColor = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant

    val bgColor = when {
        !enabled -> surfaceColor.copy(alpha = 0.4f)
        selected -> themeColor
        else -> surfaceColor
    }
    val textColor = when {
        !enabled -> onSurfaceColor.copy(alpha = 0.4f)
        selected -> onThemeColor
        else -> onSurfaceColor
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * 把选中的日期集合转成可读的说明文案
 *
 * 规则：
 *   - 空集   → "未选择任何日期（不会生效）"
 *   - 全集   → "每天"
 *   - 工作日 → "工作日（周一至周五）"
 *   - 周末   → "周末（周六、周日）"
 *   - 其他   → "周一、周三、周五"（按星期排序）
 *
 * 编号约定：1=周一 ... 7=周日
 */
private fun summarizeSelection(selected: Set<Int>): String {
    return when {
        selected.isEmpty() -> "未选择任何日期（不会生效）"
        selected.size == 7 -> "每天"
        selected == setOf(1, 2, 3, 4, 5) -> "工作日（周一至周五）"
        selected == setOf(6, 7) -> "周末（周六、周日）"
        else -> {
            val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
            val sorted = selected.sorted()
            "周" + sorted.joinToString("、") { names[it - 1] }
        }
    }
}