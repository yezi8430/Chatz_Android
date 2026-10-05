package asia.guojuice.yezigotify.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable

/**
 * 公共时间选择对话框
 *
 * 抽取原因：
 *   AppDndConfigDialog 和 DndSettingsScreen 各有一份完全相同的
 *   "rememberTimePickerState + AlertDialog + TimePicker + 确定/取消" 代码。
 *   未来 Material3 API 变动或想统一加功能（如 12/24 小时制切换），
 *   都要改两处，容易遗漏。
 *
 *   抽到 ui.common 后：
 *     - 逻辑只有一份，改一处生效两处
 *     - 调用方只需关心"初始时间、确定回调、取消回调"
 *
 * 命名说明：
 *   加 App 前缀避免与 Material3 自带的 TimePicker 语义混淆，
 *   也避免与 AppDndConfigDialog 里原先的 private TimePickerDialog 重名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        text = { TimePicker(state = state) }
    )
}