package asia.guojuice.yezigotify.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 弹窗里的分段标签栏 —— 就是「个人信息」弹窗里
 * 「头像昵称 / 用户名邮箱 / 修改密码」那一排按钮。
 *
 * ── 为什么要抽出来 ──
 * 原本只有 `ui.settings.AccountProfileDialog` 有一份私有实现。
 * 频道管理弹窗要长得跟它一样，如果各抄一份，以后改样式必然只改一处、
 * 另一处静默漂移 —— 「同一条规则散在多处」这个项目已经踩过一次
 * （见 `utils.MessageText` 里那段注释），别再犯。
 *
 * ── 为什么不用 Material3 的 `TabRow` ──
 * 项目里从没出现过 Tab 组件，而各版本 `TabRow` / `PrimaryTabRow` 的 API 有差异，
 * 手搓这排更稳，样式也更好跟主题对齐。
 *
 * ── 两个实机踩过的坑（都已修）──────────────────
 * 1. **文字被截断**：TextButton 默认 `contentPadding` 左右各 12dp，
 *    4 个汉字 ≈ 48dp + 24dp = 72dp；四个标签在弹窗里均分只有 ~70dp ⇒ 刚好截断。
 *    ⇒ `contentPadding` 收到左右 6dp，标签文案也尽量短。
 * 2. **涟漪形状跟选中背景不一致**：之前是手搓
 *    `Modifier.clip(RoundedCornerShape(8.dp)).background(...)` —— `clip` 只影响
 *    **绘制**，涟漪是 TextButton 内部 Surface 按**它自己的默认形状**（4dp）画的
 *    ⇒ 按下去的波纹比色块方、还会越出圆角。
 *    ⇒ 改成把形状交给 `TextButton(shape = ...)` + `colors(containerColor = ...)`，
 *       背景和涟漪天然是同一个圆角，也就不需要手动 clip 了。
 *
 * @param labels        从左到右的标签文案
 * @param selectedIndex 当前选中的下标
 * @param errorIndexes  有未保存错误的下标 —— 右上角打一个小圆点，
 *                      这样用户切走了也知道那边还有问题
 * @param onSelect      点击回调，参数是选中的下标
 */
@Composable
fun DialogTabBar(
    labels: List<String>,
    selectedIndex: Int,
    errorIndexes: Set<Int> = emptySet(),
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = { onSelect(index) },
                    // ⚠️ 形状必须交给 TextButton 自己：涟漪按这个形状画，
                    //    background 和涟漪才不会一个 8dp 一个 4dp 地错位
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    ),
                    // ⚠️ 默认左右各 12dp 太宽，四个标签会把文字挤到截断
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
                if (index in errorIndexes) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 8.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                }
            }
        }
    }
}
