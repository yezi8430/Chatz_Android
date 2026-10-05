package asia.guojuice.yezigotify.ui.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import asia.guojuice.yezigotify.capabilities.ChatzChannel

/**
 * 频道长按菜单
 *
 * 菜单项：全部已读 / 通知设置 / 不接收通知（恢复接收通知）/ 取消订阅。
 *
 * ⚠️ 「不接收通知」= 服务端订阅状态 `muted`，**完全不弹**（多设备同步）；
 *    与「通知设置」里那个「静默通知」不是一回事（那个是弹但不出声的本机偏好）。
 * 删除频道属于频道管理（仅创建者/管理员），不在本菜单里。
 */
@Composable
fun ChannelMenuSheet(
    channel: ChatzChannel,
    onMarkAllRead: () -> Unit,
    onOpenDndSettings: () -> Unit,
    onToggleMute: () -> Unit,
    onUnsubscribe: () -> Unit,
    /** 管理频道（改名 / 描述 / 公开性 / 图标）；传 null 则隐藏这一项 */
    onManage: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = channel.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!channel.description.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = channel.description,
                            fontSize = 12.sp,
                            color = onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                ChannelMenuRow(
                    text = "全部已读",
                    onClick = onMarkAllRead
                )
                ChannelMenuRow(
                    text = "通知设置",
                    onClick = onOpenDndSettings
                )
                ChannelMenuRow(
                    text = if (channel.muted) "恢复接收通知" else "不接收通知",
                    onClick = onToggleMute
                )
                if (onManage != null) {
                    ChannelMenuRow(
                        text = "管理频道",
                        onClick = onManage
                    )
                }
                ChannelMenuRow(
                    text = "取消订阅",
                    onClick = onUnsubscribe,
                    danger = true
                )
                ChannelMenuRow(
                    text = "取消",
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun ChannelMenuRow(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Text(
        text = text,
        fontSize = 15.sp,
        color = if (danger) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    )
}
